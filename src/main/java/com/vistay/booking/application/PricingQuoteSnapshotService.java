package com.vistay.booking.application;

import com.vistay.booking.application.PricingOverrideService.AppliedOverride;
import com.vistay.booking.application.PricingOverrideService.ManualOverride;
import com.vistay.booking.application.PricingPublicationService.Publication;
import com.vistay.booking.application.PricingQuoteCalculator.Command;
import com.vistay.booking.application.PricingQuoteCalculator.PriceComponent;
import com.vistay.booking.application.PricingQuoteCalculator.Quote;
import com.vistay.booking.application.PricingScopePolicy.ScopeEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PricingQuoteSnapshotService {
    private static final String COMMAND_TYPE = "CREATE_PROPERTY_PRICE_QUOTE_SNAPSHOT";
    private static final String PERMISSION = "pricing:read";
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("^[A-Za-z0-9._:-]{8,160}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final PricingPublicationService publications;
    private final PricingQuoteCalculator calculator;
    private final PricingOverrideService overrides;
    private final Clock clock;

    public PricingQuoteSnapshotService(
        JdbcClient jdbc,
        ObjectMapper json,
        PricingPublicationService publications,
        PricingQuoteCalculator calculator,
        PricingOverrideService overrides,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.json = json;
        this.publications = publications;
        this.calculator = calculator;
        this.overrides = overrides;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublishedQuotePreview preview(Input input) {
        validateInput(input);
        var active = publications.active(input.propertyId(), input.checkInDate(),
            input.checkOutDate());
        var command = input.command(active.profile().id());
        return new PublishedQuotePreview(active.publication(),
            calculator.calculate(active.profile(), command));
    }

    @Transactional
    public QuoteSnapshot create(Draft draft, AuthorizationEvidence authorization) {
        validateDraft(draft, authorization);
        var requestHash = requestHash(draft);
        var durable = snapshotForKey(draft.idempotencyKey());
        if (durable.isPresent()) {
            return replay(durable.get(), authorization.actorAccountId(), requestHash);
        }

        int inserted = jdbc.sql("""
                INSERT INTO command_idempotency(
                    idempotency_key, command_type, actor_account_type, actor_account_id,
                    request_hash, status, expires_at
                ) VALUES (
                    :key, :commandType, 'ADMIN', :actorId, :requestHash, 'PROCESSING',
                    now() + interval '30 days'
                )
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .param("key", draft.idempotencyKey())
            .param("commandType", COMMAND_TYPE)
            .param("actorId", authorization.actorAccountId())
            .param("requestHash", requestHash)
            .update();
        if (inserted == 0) {
            return replay(command(draft.idempotencyKey()).orElseThrow(),
                authorization.actorAccountId(), requestHash);
        }

        acquirePropertyLock(draft.input().propertyId());
        var active = publications.active(draft.input().propertyId(),
            draft.input().checkInDate(), draft.input().checkOutDate());
        require(active.profile().id().equals(draft.expectedRateProfileVersionId()),
            "STALE_PUBLISHED_PRICING",
            "The active published pricing profile changed after preview");
        var quote = calculator.calculate(active.profile(),
            draft.input().command(active.profile().id()));
        require(quote.fingerprint().equals(draft.expectedBaseQuoteFingerprint()),
            "STALE_BASE_QUOTE", "The published quote changed after preview");

        AppliedOverride applied = null;
        if (draft.manualOverrideId() != null) {
            lockOverride(draft.manualOverrideId());
            require(!overrideConsumed(draft.manualOverrideId()),
                "PRICING_OVERRIDE_ALREADY_CONSUMED",
                "Pricing manual override has already been consumed by another quote snapshot");
            applied = overrides.require(draft.manualOverrideId());
            validateOverride(applied, quote);
        }
        var finalQuote = finalQuote(quote, applied);
        var createdAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
        var expiresAt = createdAt.plusMinutes(active.profile().quoteTtlMinutes());
        var quoteId = UUID.randomUUID();

        long subtotal = amount(quote.lodgingBeforeAdjustments(), "lodging before adjustments");
        long baseDiscount = amountAllowZero(quote.discountAmount(), "discount amount");
        long manualReduction = subtract(amount(quote.lodgingAmount(), "lodging amount"),
            amountAllowZero(finalQuote.lodgingAmount(), "final lodging amount"));
        long discount = add(baseDiscount, manualReduction);
        long surcharge = amountAllowZero(quote.surchargeAmount(), "surcharge amount");
        long commission = amountAllowZero(finalQuote.commissionAmount(), "commission amount");
        long fees = add(surcharge, commission);
        long total = amountAllowZero(finalQuote.totalAmount(), "final total amount");

        var policySnapshot = new LinkedHashMap<String, Object>();
        policySnapshot.put("publication", active.publication());
        policySnapshot.put("profile", active.profile());
        jdbc.sql("""
                INSERT INTO booking_quotes(
                    id, property_id, check_in_date, check_out_date, adults, children,
                    price_unit, duration_value, unit_price, subtotal_amount,
                    discount_amount, fee_amount, tax_amount, total_amount, currency,
                    price_breakdown, policy_snapshot, status, expires_at, created_at,
                    idempotency_key, request_hash, pricing_publication_id,
                    rate_profile_version_id, manual_override_id,
                    base_quote_fingerprint, calculation_version,
                    quote_input_snapshot, base_quote_snapshot, final_quote_snapshot,
                    created_by_admin_account_id, created_by_admin_profile_id,
                    authorization_version, permission_code, scope_type, partition_id,
                    reason, correlation_id
                ) VALUES (
                    :id, :propertyId, :checkIn, :checkOut, :guestCount, 0,
                    'STAY', :stayDays, :unitPrice, :subtotal,
                    :discount, :fees, 0, :total, :currency,
                    CAST(:priceBreakdown AS jsonb), CAST(:policySnapshot AS jsonb),
                    'ACTIVE', :expiresAt, :createdAt,
                    :key, :requestHash, :publicationId, :profileId, :overrideId,
                    :fingerprint, :calculationVersion,
                    CAST(:quoteInput AS jsonb), CAST(:baseQuote AS jsonb),
                    CAST(:finalQuote AS jsonb), :actorAccountId, :actorProfileId,
                    :authorizationVersion, :permissionCode, :scopeType, :partitionId,
                    :reason, :correlationId
                )
                """)
            .param("id", quoteId)
            .param("propertyId", quote.propertyId())
            .param("checkIn", draft.input().checkInDate())
            .param("checkOut", draft.input().checkOutDate())
            .param("guestCount", draft.input().guestCount())
            .param("stayDays", quote.stayDays())
            .param("unitPrice", amountAllowZero(quote.productDailyRate(), "product daily rate"))
            .param("subtotal", subtotal)
            .param("discount", discount)
            .param("fees", fees)
            .param("total", total)
            .param("currency", quote.currency())
            .param("priceBreakdown", writeJson(finalQuote))
            .param("policySnapshot", writeJson(policySnapshot))
            .param("expiresAt", expiresAt)
            .param("createdAt", createdAt)
            .param("key", draft.idempotencyKey())
            .param("requestHash", requestHash)
            .param("publicationId", active.publication().id())
            .param("profileId", active.profile().id())
            .param("overrideId", draft.manualOverrideId())
            .param("fingerprint", quote.fingerprint())
            .param("calculationVersion", quote.calculationVersion())
            .param("quoteInput", writeJson(draft.input()))
            .param("baseQuote", writeJson(quote))
            .param("finalQuote", writeJson(finalQuote))
            .param("actorAccountId", authorization.actorAccountId())
            .param("actorProfileId", authorization.actorProfileId())
            .param("authorizationVersion", authorization.authorizationVersion())
            .param("permissionCode", PERMISSION)
            .param("scopeType", authorization.scope().type())
            .param("partitionId", authorization.scope().partitionId())
            .param("reason", draft.reason())
            .param("correlationId", draft.correlationId())
            .update();

        var changes = new LinkedHashMap<String, Object>();
        changes.put("propertyId", quote.propertyId().toString());
        changes.put("pricingPublicationId", active.publication().id().toString());
        changes.put("rateProfileVersionId", active.profile().id().toString());
        changes.put("baseQuoteFingerprint", quote.fingerprint());
        changes.put("manualOverrideId",
            draft.manualOverrideId() == null ? null : draft.manualOverrideId().toString());
        changes.put("totalAmount", total);
        changes.put("authorizationVersion", authorization.authorizationVersion());
        changes.put("scopeType", authorization.scope().type());
        changes.put("partitionId", authorization.scope().partitionId());
        changes.put("reason", draft.reason());
        jdbc.sql("""
                INSERT INTO audit_events(
                    actor_account_type, actor_account_id, action, aggregate_type,
                    aggregate_id, changes, correlation_id, created_at
                ) VALUES (
                    'ADMIN', :actorId, 'PRICING_QUOTE_SNAPSHOT_CREATED',
                    'BookingQuote', :quoteId, CAST(:changes AS jsonb),
                    :correlationId, :createdAt
                )
                """)
            .param("actorId", authorization.actorAccountId())
            .param("quoteId", quoteId)
            .param("changes", writeJson(changes))
            .param("correlationId", draft.correlationId())
            .param("createdAt", createdAt)
            .update();

        jdbc.sql("""
                UPDATE command_idempotency
                SET resource_type = 'BookingQuote', resource_id = :resourceId,
                    response_code = 200, response_body = CAST(:response AS jsonb),
                    status = 'SUCCEEDED', completed_at = now()
                WHERE idempotency_key = :key
                """)
            .param("resourceId", quoteId)
            .param("response", writeJson(Map.of("quoteId", quoteId.toString())))
            .param("key", draft.idempotencyKey())
            .update();
        return requireSnapshot(quoteId);
    }

    @Transactional(readOnly = true)
    public QuoteSnapshot requireSnapshot(UUID id) {
        var stored = jdbc.sql("""
                SELECT id, idempotency_key, request_hash, property_id,
                       pricing_publication_id, rate_profile_version_id, manual_override_id,
                       base_quote_fingerprint, calculation_version,
                       base_quote_snapshot::text AS base_quote_snapshot,
                       final_quote_snapshot::text AS final_quote_snapshot,
                       created_by_admin_account_id, created_by_admin_profile_id,
                       authorization_version, permission_code, scope_type, partition_id,
                       reason, correlation_id, status, currency, total_amount,
                       created_at, expires_at
                FROM booking_quotes
                WHERE id = :id AND pricing_publication_id IS NOT NULL
                """)
            .param("id", id)
            .query(this::mapStored)
            .optional()
            .orElseThrow(() -> new PricingPolicyException("PRICING_QUOTE_SNAPSHOT_NOT_FOUND",
                "Pricing quote snapshot was not found"));
        var publication = publications.require(stored.pricingPublicationId());
        var baseQuote = readQuote(stored.baseQuoteSnapshot());
        var finalQuote = readFinalQuote(stored.finalQuoteSnapshot());
        if (!stored.propertyId().equals(publication.propertyId())
            || !stored.rateProfileVersionId().equals(publication.rateProfileVersionId())
            || !stored.propertyId().equals(baseQuote.propertyId())
            || !stored.rateProfileVersionId().equals(baseQuote.rateProfileVersionId())
            || !stored.baseQuoteFingerprint().equals(baseQuote.fingerprint())
            || !stored.calculationVersion().equals(baseQuote.calculationVersion())
            || !stored.currency().equals(baseQuote.currency())
            || !stored.totalAmount().equals(finalQuote.totalAmount())
            || !baseQuote.equals(finalQuote.baseQuote())
            || !sameOverride(stored.manualOverrideId(), finalQuote.manualOverride())) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_SNAPSHOT",
                "Stored pricing quote evidence is inconsistent");
        }
        return stored.toSnapshot(publication, baseQuote, finalQuote);
    }

    private void validateDraft(Draft draft, AuthorizationEvidence authorization) {
        require(draft != null && authorization != null,
            "INVALID_QUOTE_SNAPSHOT_INPUT", "Quote snapshot input is required");
        validateInput(draft.input());
        require(draft.idempotencyKey() != null
                && IDEMPOTENCY_KEY.matcher(draft.idempotencyKey()).matches(),
            "INVALID_IDEMPOTENCY_KEY", "Idempotency key has an invalid format");
        require(draft.expectedRateProfileVersionId() != null,
            "INVALID_QUOTE_SNAPSHOT_INPUT", "Expected profile version is required");
        require(draft.expectedBaseQuoteFingerprint() != null
                && FINGERPRINT.matcher(draft.expectedBaseQuoteFingerprint()).matches(),
            "INVALID_BASE_QUOTE_FINGERPRINT", "Expected base quote fingerprint is invalid");
        require(trimmedLength(draft.reason(), 10, 1000),
            "INVALID_QUOTE_SNAPSHOT_REASON", "Snapshot reason must contain 10 to 1000 characters");
        require(isCanonicalUuid(draft.correlationId()),
            "INVALID_CORRELATION_ID", "Correlation ID must be a canonical UUID");
        require(authorization.actorAccountId() != null
                && authorization.actorProfileId() != null
                && authorization.authorizationVersion() >= 1
                && validScope(authorization.scope()),
            "INVALID_QUOTE_SNAPSHOT_AUTHORIZATION",
            "Quote snapshot authorization evidence is invalid");
    }

    private static void validateInput(Input input) {
        require(input != null && input.propertyId() != null
                && input.checkInDate() != null && input.checkOutDate() != null
                && input.checkOutDate().isAfter(input.checkInDate()),
            "INVALID_STAY_RANGE", "Checkout must be after check-in");
        require(input.guestCount() >= 1 && input.guestCount() <= 100,
            "INVALID_GUEST_COUNT", "Guest count must be between 1 and 100");
        require(input.channel() != null, "INVALID_PRICING_CHANNEL",
            "Pricing channel is required");
    }

    private void validateOverride(AppliedOverride applied, Quote recalculated) {
        var evidence = applied.manualOverride();
        var expiresAt = OffsetDateTime.parse(evidence.expiresAt());
        require(expiresAt.isAfter(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)),
            "PRICING_OVERRIDE_EXPIRED", "Pricing manual override expired before consumption");
        require(evidence.id().equals(applied.manualOverride().id())
                && evidence.propertyId().equals(recalculated.propertyId())
                && evidence.rateProfileVersionId().equals(recalculated.rateProfileVersionId())
                && evidence.baseQuoteFingerprint().equals(recalculated.fingerprint())
                && evidence.calculationVersion().equals(recalculated.calculationVersion())
                && applied.baseQuote().equals(recalculated),
            "PRICING_OVERRIDE_MISMATCH",
            "Pricing manual override does not match the published quote being persisted");
    }

    private void lockOverride(UUID overrideId) {
        var exists = jdbc.sql("""
                SELECT id FROM pricing_manual_overrides WHERE id = :id FOR UPDATE
                """)
            .param("id", overrideId)
            .query(UUID.class)
            .optional();
        if (exists.isEmpty()) {
            throw new PricingPolicyException("PRICING_OVERRIDE_NOT_FOUND",
                "Pricing manual override was not found");
        }
    }

    private boolean overrideConsumed(UUID overrideId) {
        return jdbc.sql("""
                SELECT EXISTS(
                    SELECT 1 FROM booking_quotes WHERE manual_override_id = :overrideId
                )
                """)
            .param("overrideId", overrideId)
            .query(Boolean.class)
            .single();
    }

    private FinalQuote finalQuote(Quote baseQuote, AppliedOverride applied) {
        if (applied == null) {
            return new FinalQuote(baseQuote, null, baseQuote.precedence(),
                baseQuote.components(), baseQuote.lodgingAmount(),
                baseQuote.commissionAmount(), baseQuote.totalAmount());
        }
        return new FinalQuote(baseQuote, applied.manualOverride(),
            applied.finalPrecedence(), applied.finalComponents(),
            applied.finalLodgingAmount(), applied.finalCommissionAmount(),
            applied.finalTotalAmount());
    }

    private QuoteSnapshot replay(CommandRow row, UUID actorId, String requestHash) {
        if (!COMMAND_TYPE.equals(row.commandType())
            || !actorId.equals(row.actorAccountId())
            || !requestHash.equals(row.requestHash())) {
            throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different pricing command");
        }
        if (!"SUCCEEDED".equals(row.status()) || row.resourceId() == null) {
            throw new PricingPolicyException("IDEMPOTENCY_COMMAND_INCOMPLETE",
                "The previous quote snapshot command has no durable result");
        }
        return requireSnapshot(row.resourceId());
    }

    private QuoteSnapshot replay(StoredSnapshot row, UUID actorId, String requestHash) {
        if (!actorId.equals(row.actorAdminAccountId())
            || !requestHash.equals(row.requestHash())) {
            throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different pricing command");
        }
        return requireSnapshot(row.id());
    }

    private Optional<CommandRow> command(String key) {
        return jdbc.sql("""
                SELECT command_type, actor_account_id, request_hash, resource_id, status
                FROM command_idempotency WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query((rs, rowNumber) -> new CommandRow(
                rs.getString("command_type"), rs.getObject("actor_account_id", UUID.class),
                rs.getString("request_hash").trim(), rs.getObject("resource_id", UUID.class),
                rs.getString("status")))
            .optional();
    }

    private Optional<StoredSnapshot> snapshotForKey(String key) {
        return jdbc.sql("""
                SELECT id, idempotency_key, request_hash, property_id,
                       pricing_publication_id, rate_profile_version_id, manual_override_id,
                       base_quote_fingerprint, calculation_version,
                       base_quote_snapshot::text AS base_quote_snapshot,
                       final_quote_snapshot::text AS final_quote_snapshot,
                       created_by_admin_account_id, created_by_admin_profile_id,
                       authorization_version, permission_code, scope_type, partition_id,
                       reason, correlation_id, status, currency, total_amount,
                       created_at, expires_at
                FROM booking_quotes
                WHERE idempotency_key = :key AND pricing_publication_id IS NOT NULL
                """)
            .param("key", key)
            .query(this::mapStored)
            .optional();
    }

    private StoredSnapshot mapStored(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredSnapshot(
            rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
            rs.getString("request_hash").trim(), rs.getObject("property_id", UUID.class),
            rs.getObject("pricing_publication_id", UUID.class),
            rs.getObject("rate_profile_version_id", UUID.class),
            rs.getObject("manual_override_id", UUID.class),
            rs.getString("base_quote_fingerprint").trim(),
            rs.getString("calculation_version"), rs.getString("base_quote_snapshot"),
            rs.getString("final_quote_snapshot"),
            rs.getObject("created_by_admin_account_id", UUID.class),
            rs.getObject("created_by_admin_profile_id", UUID.class),
            rs.getLong("authorization_version"), rs.getString("permission_code"),
            rs.getString("scope_type"), rs.getObject("partition_id", UUID.class),
            rs.getString("reason"), rs.getString("correlation_id"), rs.getString("status"),
            rs.getString("currency").trim(),
            rs.getBigDecimal("total_amount").stripTrailingZeros().toPlainString(),
            rs.getObject("created_at", OffsetDateTime.class).toString(),
            rs.getObject("expires_at", OffsetDateTime.class).toString()
        );
    }

    private void acquirePropertyLock(UUID propertyId) {
        jdbc.sql("""
                SELECT hashtextextended(CAST(:propertyId AS text), 0)
                FROM (SELECT pg_advisory_xact_lock(
                    hashtextextended(CAST(:propertyId AS text), 0))) AS acquired
                """)
            .param("propertyId", propertyId)
            .query(Long.class)
            .single();
    }

    private String requestHash(Draft draft) {
        var canonical = new StringBuilder();
        append(canonical, COMMAND_TYPE);
        append(canonical, draft.idempotencyKey());
        append(canonical, draft.input().propertyId());
        append(canonical, draft.input().checkInDate());
        append(canonical, draft.input().checkOutDate());
        append(canonical, draft.input().guestCount());
        append(canonical, draft.input().channel());
        append(canonical, draft.input().staffCreated());
        append(canonical, draft.input().commissionBps());
        append(canonical, draft.expectedRateProfileVersionId());
        append(canonical, draft.expectedBaseQuoteFingerprint());
        append(canonical, draft.manualOverrideId());
        append(canonical, draft.reason());
        append(canonical, draft.correlationId());
        return sha256(canonical.toString());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cannot serialize pricing quote evidence", failure);
        }
    }

    private Quote readQuote(String value) {
        try {
            return json.readValue(value, Quote.class);
        } catch (RuntimeException failure) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_SNAPSHOT",
                "Stored base quote snapshot is invalid");
        }
    }

    private FinalQuote readFinalQuote(String value) {
        try {
            return json.readValue(value, FinalQuote.class);
        } catch (RuntimeException failure) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_SNAPSHOT",
                "Stored final quote snapshot is invalid");
        }
    }

    private static boolean sameOverride(UUID expected, ManualOverride actual) {
        return expected == null ? actual == null : actual != null && expected.equals(actual.id());
    }

    private static long amount(String value, String field) {
        long parsed = amountAllowZero(value, field);
        require(parsed > 0, "INVALID_STORED_PRICING_POLICY", field + " must be positive");
        return parsed;
    }

    private static long amountAllowZero(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            require(parsed >= 0, "INVALID_STORED_PRICING_POLICY",
                field + " must not be negative");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_POLICY",
                field + " is not a whole-VND amount");
        }
    }

    private static long add(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new PricingPolicyException("PRICE_OVERFLOW",
                "Calculated pricing exceeds VND limits");
        }
    }

    private static long subtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new PricingPolicyException("PRICE_OVERFLOW",
                "Calculated pricing exceeds VND limits");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, Object value) {
        var text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append(';');
    }

    private static boolean trimmedLength(String value, int minimum, int maximum) {
        return value != null && value.equals(value.trim())
            && value.codePointCount(0, value.length()) >= minimum
            && value.codePointCount(0, value.length()) <= maximum;
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) return false;
        try {
            return value.equals(UUID.fromString(value).toString());
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean validScope(ScopeEvidence scope) {
        return scope != null && ("ALL".equals(scope.type()) && scope.partitionId() == null
            || "BRANCH".equals(scope.type()) && scope.partitionId() != null);
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new PricingPolicyException(code, message);
    }

    private record CommandRow(
        String commandType,
        UUID actorAccountId,
        String requestHash,
        UUID resourceId,
        String status
    ) {}

    private record StoredSnapshot(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID propertyId,
        UUID pricingPublicationId,
        UUID rateProfileVersionId,
        UUID manualOverrideId,
        String baseQuoteFingerprint,
        String calculationVersion,
        String baseQuoteSnapshot,
        String finalQuoteSnapshot,
        UUID actorAdminAccountId,
        UUID actorAdminProfileId,
        long authorizationVersion,
        String permissionCode,
        String scopeType,
        UUID partitionId,
        String reason,
        String correlationId,
        String status,
        String currency,
        String totalAmount,
        String createdAt,
        String expiresAt
    ) {
        QuoteSnapshot toSnapshot(
            Publication publication,
            Quote baseQuote,
            FinalQuote finalQuote
        ) {
            return new QuoteSnapshot(id, idempotencyKey, requestHash, propertyId,
                pricingPublicationId, rateProfileVersionId, manualOverrideId,
                baseQuoteFingerprint, calculationVersion, publication, baseQuote,
                finalQuote, actorAdminAccountId, actorAdminProfileId,
                authorizationVersion, permissionCode, scopeType, partitionId,
                reason, correlationId, status, currency, createdAt, expiresAt);
        }
    }

    public record Input(
        UUID propertyId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int guestCount,
        String channel,
        boolean staffCreated,
        Integer commissionBps
    ) {
        Command command(UUID profileVersionId) {
            return new Command(profileVersionId, propertyId, checkInDate, checkOutDate,
                guestCount, channel, staffCreated, commissionBps);
        }
    }

    public record Draft(
        String idempotencyKey,
        Input input,
        UUID expectedRateProfileVersionId,
        String expectedBaseQuoteFingerprint,
        UUID manualOverrideId,
        String reason,
        String correlationId
    ) {}

    public record AuthorizationEvidence(
        UUID actorAccountId,
        UUID actorProfileId,
        long authorizationVersion,
        ScopeEvidence scope
    ) {}

    public record PublishedQuotePreview(Publication publication, Quote baseQuote) {}

    public record FinalQuote(
        Quote baseQuote,
        ManualOverride manualOverride,
        List<String> precedence,
        List<PriceComponent> components,
        String lodgingAmount,
        String commissionAmount,
        String totalAmount
    ) {}

    public record QuoteSnapshot(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID propertyId,
        UUID pricingPublicationId,
        UUID rateProfileVersionId,
        UUID manualOverrideId,
        String baseQuoteFingerprint,
        String calculationVersion,
        Publication publication,
        Quote baseQuote,
        FinalQuote finalQuote,
        UUID actorAdminAccountId,
        UUID actorAdminProfileId,
        long authorizationVersion,
        String permissionCode,
        String scopeType,
        UUID partitionId,
        String reason,
        String correlationId,
        String status,
        String currency,
        String createdAt,
        String expiresAt
    ) {}
}
