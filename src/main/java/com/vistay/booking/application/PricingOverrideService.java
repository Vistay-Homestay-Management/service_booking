package com.vistay.booking.application;

import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingQuoteCalculator.Command;
import com.vistay.booking.application.PricingQuoteCalculator.PriceComponent;
import com.vistay.booking.application.PricingQuoteCalculator.PriceComponentType;
import com.vistay.booking.application.PricingQuoteCalculator.Quote;
import com.vistay.booking.application.PricingScopePolicy.ScopeEvidence;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
public class PricingOverrideService {
    private static final String COMMAND_TYPE = "APPLY_PRICING_MANUAL_OVERRIDE";
    private static final String OVERRIDE_PERMISSION = "pricing:override";
    private static final String LARGE_OVERRIDE_PERMISSION =
        "pricing:approve-large-override";
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("^[A-Za-z0-9._:-]{8,160}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");
    private static final String BASE_COMMISSION_PRECEDENCE =
        "COMMISSION:lodging-subtotal-after-rules";
    private static final String OVERRIDE_PRECEDENCE =
        "MANUAL_OVERRIDE:whole-stay-lodging-reduction";
    private static final String FINAL_COMMISSION_PRECEDENCE =
        "COMMISSION:recalculated-after-manual-override";

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public PricingOverrideService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<AppliedOverride> replay(UUID actorAccountId, Draft draft) {
        validateCommandMetadata(draft);
        var requestHash = requestHash(draft);
        var existing = command(draft.idempotencyKey());
        if (existing.isPresent()) {
            return Optional.of(replay(existing.get(), actorAccountId, requestHash));
        }
        return artifact(draft.idempotencyKey())
            .map(row -> replay(row, actorAccountId, requestHash));
    }

    @Transactional
    public AppliedOverride apply(
        ProfileVersion profile,
        Quote baseQuote,
        Draft draft,
        AuthorizationEvidence authorization
    ) {
        var calculation = validateAndCalculate(profile, baseQuote, draft, authorization);
        var requestHash = requestHash(draft);
        var durableArtifact = artifact(draft.idempotencyKey());
        if (durableArtifact.isPresent()) {
            return replay(durableArtifact.get(), authorization.actorAccountId(), requestHash);
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

        var id = UUID.randomUUID();
        // PostgreSQL timestamptz stores microseconds. Normalize before both the
        // immutable JSON snapshot and relational columns so replay is identical.
        var createdAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
        var expiresAt = createdAt.plusMinutes(profile.quoteTtlMinutes());
        var manualOverride = new ManualOverride(
            id, draft.idempotencyKey(), requestHash, profile.propertyId(), profile.id(),
            baseQuote.fingerprint(), baseQuote.calculationVersion(),
            text(calculation.beforeLodging()), text(draft.targetLodgingAmount()),
            text(calculation.adjustment()), calculation.reductionBps(),
            baseQuote.commissionBps(), text(calculation.beforeCommission()),
            text(calculation.afterCommission()), text(calculation.beforeTotal()),
            text(calculation.afterTotal()), baseQuote.currency(), calculation.largeApprovalRequired(),
            authorization.actorAccountId(), authorization.actorProfileId(),
            authorization.authorizationVersion(), OVERRIDE_PERMISSION,
            authorization.overrideScope().type(),
            authorization.overrideScope().partitionId(),
            authorization.largeApprovalAuthorizationVersion(),
            calculation.largeApprovalRequired() ? LARGE_OVERRIDE_PERMISSION : null,
            authorization.largeApprovalScope() == null
                ? null : authorization.largeApprovalScope().type(),
            authorization.largeApprovalScope() == null
                ? null : authorization.largeApprovalScope().partitionId(),
            draft.reason(), draft.correlationId(), profile.quoteTtlMinutes(),
            createdAt.toString(), expiresAt.toString()
        );
        var applied = applied(baseQuote, manualOverride);

        jdbc.sql("""
                INSERT INTO pricing_manual_overrides(
                    id, idempotency_key, request_hash, property_id, rate_profile_version_id,
                    base_quote_fingerprint, calculation_version,
                    quote_input_snapshot, base_quote_snapshot, applied_override_snapshot,
                    before_lodging_amount, target_lodging_amount, adjustment_amount,
                    reduction_bps, commission_bps, before_commission_amount,
                    after_commission_amount, before_total_amount, after_total_amount,
                    currency, large_approval_required, actor_admin_account_id,
                    actor_admin_profile_id, authorization_version,
                    override_permission_code, override_scope_type, override_partition_id,
                    large_approval_authorization_version, large_approval_permission_code,
                    large_approval_scope_type, large_approval_partition_id,
                    reason, correlation_id, quote_ttl_minutes, created_at, expires_at
                ) VALUES (
                    :id, :idempotencyKey, :requestHash, :propertyId, :profileId,
                    :fingerprint, :calculationVersion,
                    CAST(:quoteInput AS jsonb), CAST(:baseQuote AS jsonb),
                    CAST(:appliedOverride AS jsonb),
                    :beforeLodging, :targetLodging, :adjustment,
                    :reductionBps, :commissionBps, :beforeCommission,
                    :afterCommission, :beforeTotal, :afterTotal,
                    :currency, :largeRequired, :actorAccountId,
                    :actorProfileId, :authorizationVersion, :overridePermissionCode,
                    :overrideScopeType, :overridePartitionId, :largeAuthorizationVersion,
                    :largePermissionCode,
                    :largeScopeType, :largePartitionId,
                    :reason, :correlationId, :quoteTtlMinutes, :createdAt, :expiresAt
                )
                """)
            .param("id", id)
            .param("idempotencyKey", draft.idempotencyKey())
            .param("requestHash", requestHash)
            .param("propertyId", profile.propertyId())
            .param("profileId", profile.id())
            .param("fingerprint", baseQuote.fingerprint())
            .param("calculationVersion", baseQuote.calculationVersion())
            .param("quoteInput", writeJson(quoteInputSnapshot(draft.quote())))
            .param("baseQuote", writeJson(baseQuote))
            .param("appliedOverride", writeJson(new AppliedSnapshot(
                manualOverride, applied.finalPrecedence(), applied.finalComponents(),
                applied.finalLodgingAmount(), applied.finalCommissionAmount(),
                applied.finalTotalAmount())))
            .param("beforeLodging", calculation.beforeLodging())
            .param("targetLodging", draft.targetLodgingAmount())
            .param("adjustment", calculation.adjustment())
            .param("reductionBps", calculation.reductionBps())
            .param("commissionBps", baseQuote.commissionBps())
            .param("beforeCommission", calculation.beforeCommission())
            .param("afterCommission", calculation.afterCommission())
            .param("beforeTotal", calculation.beforeTotal())
            .param("afterTotal", calculation.afterTotal())
            .param("currency", baseQuote.currency())
            .param("largeRequired", calculation.largeApprovalRequired())
            .param("actorAccountId", authorization.actorAccountId())
            .param("actorProfileId", authorization.actorProfileId())
            .param("authorizationVersion", authorization.authorizationVersion())
            .param("overridePermissionCode", OVERRIDE_PERMISSION)
            .param("overrideScopeType", authorization.overrideScope().type())
            .param("overridePartitionId", authorization.overrideScope().partitionId())
            .param("largeAuthorizationVersion",
                authorization.largeApprovalAuthorizationVersion())
            .param("largePermissionCode", calculation.largeApprovalRequired()
                ? LARGE_OVERRIDE_PERMISSION : null)
            .param("largeScopeType", authorization.largeApprovalScope() == null
                ? null : authorization.largeApprovalScope().type())
            .param("largePartitionId", authorization.largeApprovalScope() == null
                ? null : authorization.largeApprovalScope().partitionId())
            .param("reason", draft.reason())
            .param("correlationId", draft.correlationId())
            .param("quoteTtlMinutes", profile.quoteTtlMinutes())
            .param("createdAt", createdAt)
            .param("expiresAt", expiresAt)
            .update();

        var audit = new LinkedHashMap<String, Object>();
        audit.put("propertyId", profile.propertyId().toString());
        audit.put("rateProfileVersionId", profile.id().toString());
        audit.put("baseQuoteFingerprint", baseQuote.fingerprint());
        audit.put("beforeLodgingAmount", calculation.beforeLodging());
        audit.put("targetLodgingAmount", draft.targetLodgingAmount());
        audit.put("reductionBps", calculation.reductionBps());
        audit.put("largeApprovalRequired", calculation.largeApprovalRequired());
        audit.put("actorAdminProfileId", authorization.actorProfileId().toString());
        audit.put("authorizationVersion", authorization.authorizationVersion());
        audit.put("overridePermissionCode", OVERRIDE_PERMISSION);
        audit.put("overrideScopeType", authorization.overrideScope().type());
        audit.put("overridePartitionId", authorization.overrideScope().partitionId());
        audit.put("largeApprovalAuthorizationVersion",
            authorization.largeApprovalAuthorizationVersion());
        audit.put("largeApprovalPermissionCode", calculation.largeApprovalRequired()
            ? LARGE_OVERRIDE_PERMISSION : null);
        audit.put("largeApprovalScopeType", authorization.largeApprovalScope() == null
            ? null : authorization.largeApprovalScope().type());
        audit.put("largeApprovalPartitionId", authorization.largeApprovalScope() == null
            ? null : authorization.largeApprovalScope().partitionId());
        audit.put("reason", draft.reason());
        audit.put("expiresAt", expiresAt.toString());
        jdbc.sql("""
                INSERT INTO audit_events(
                    actor_account_type, actor_account_id, action, aggregate_type,
                    aggregate_id, changes, correlation_id
                ) VALUES (
                    'ADMIN', :actorId, 'PRICING_MANUAL_OVERRIDE_APPLIED',
                    'PricingManualOverride', :overrideId, CAST(:changes AS jsonb),
                    :correlationId
                )
                """)
            .param("actorId", authorization.actorAccountId())
            .param("overrideId", id)
            .param("changes", writeJson(audit))
            .param("correlationId", draft.correlationId())
            .update();

        jdbc.sql("""
                UPDATE command_idempotency
                SET resource_type = 'PricingManualOverride', resource_id = :resourceId,
                    response_code = 200, response_body = CAST(:response AS jsonb),
                    status = 'SUCCEEDED', completed_at = now()
                WHERE idempotency_key = :key
                """)
            .param("resourceId", id)
            .param("response", writeJson(Map.of("overrideId", id.toString())))
            .param("key", draft.idempotencyKey())
            .update();
        return applied;
    }

    @Transactional(readOnly = true)
    public AppliedOverride require(UUID id) {
        var stored = jdbc.sql("""
                SELECT id, idempotency_key, request_hash, property_id,
                       rate_profile_version_id, base_quote_fingerprint, calculation_version,
                       base_quote_snapshot::text AS base_quote_snapshot,
                       applied_override_snapshot::text AS applied_override_snapshot,
                       before_lodging_amount, target_lodging_amount, adjustment_amount,
                       reduction_bps, commission_bps, before_commission_amount,
                       after_commission_amount, before_total_amount, after_total_amount,
                       currency, large_approval_required, actor_admin_account_id,
                       actor_admin_profile_id, authorization_version,
                       override_permission_code, override_scope_type, override_partition_id,
                       large_approval_authorization_version, large_approval_permission_code,
                       large_approval_scope_type, large_approval_partition_id,
                       reason, correlation_id, quote_ttl_minutes, created_at, expires_at
                FROM pricing_manual_overrides
                WHERE id = :id
                """)
            .param("id", id)
            .query(this::mapStored)
            .optional()
            .orElseThrow(() -> new PricingPolicyException("PRICING_OVERRIDE_NOT_FOUND",
                "Pricing manual override was not found"));
        var baseQuote = readQuote(stored.baseQuoteSnapshot());
        var snapshot = readAppliedSnapshot(stored.appliedOverrideSnapshot());
        if (!stored.manualOverride().equals(snapshot.manualOverride())
            || !stored.manualOverride().baseQuoteFingerprint().equals(baseQuote.fingerprint())
            || !stored.manualOverride().propertyId().equals(baseQuote.propertyId())
            || !stored.manualOverride().rateProfileVersionId()
                .equals(baseQuote.rateProfileVersionId())
            || !stored.manualOverride().calculationVersion()
                .equals(baseQuote.calculationVersion())
            || !stored.manualOverride().currency().equals(baseQuote.currency())
            || !stored.manualOverride().beforeLodgingAmount()
                .equals(baseQuote.lodgingAmount())
            || !stored.manualOverride().beforeCommissionAmount()
                .equals(baseQuote.commissionAmount())
            || !stored.manualOverride().beforeTotalAmount().equals(baseQuote.totalAmount())
            || snapshot.finalPrecedence() == null
            || snapshot.finalComponents() == null
            || !stored.manualOverride().targetLodgingAmount()
                .equals(snapshot.finalLodgingAmount())
            || !stored.manualOverride().afterCommissionAmount()
                .equals(snapshot.finalCommissionAmount())
            || !stored.manualOverride().afterTotalAmount()
                .equals(snapshot.finalTotalAmount())) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_OVERRIDE",
                "Stored pricing override snapshots do not match immutable evidence");
        }
        return new AppliedOverride(baseQuote, stored.manualOverride(),
            List.copyOf(snapshot.finalPrecedence()),
            List.copyOf(snapshot.finalComponents()),
            snapshot.finalLodgingAmount(), snapshot.finalCommissionAmount(),
            snapshot.finalTotalAmount());
    }

    public static boolean requiresLargeApproval(long beforeLodging, long targetLodging) {
        if (beforeLodging <= 0 || targetLodging < 0 || targetLodging >= beforeLodging) {
            return false;
        }
        var reduction = BigInteger.valueOf(beforeLodging - targetLodging)
            .multiply(BigInteger.valueOf(10_000));
        var threshold = BigInteger.valueOf(beforeLodging).multiply(BigInteger.valueOf(2_000));
        return reduction.compareTo(threshold) > 0;
    }

    private Calculation validateAndCalculate(
        ProfileVersion profile,
        Quote baseQuote,
        Draft draft,
        AuthorizationEvidence authorization
    ) {
        validateCommandMetadata(draft);
        require(profile != null && baseQuote != null && authorization != null,
            "INVALID_OVERRIDE_INPUT", "Profile, base quote, and authorization are required");
        require(profile.id().equals(draft.quote().rateProfileVersionId())
                && profile.propertyId().equals(draft.quote().propertyId())
                && profile.id().equals(baseQuote.rateProfileVersionId())
                && profile.propertyId().equals(baseQuote.propertyId()),
            "PRICING_OVERRIDE_SUBJECT_MISMATCH",
            "Override profile, property, and base quote must identify the same subject");
        require(draft.expectedBaseQuoteFingerprint().equals(baseQuote.fingerprint()),
            "STALE_BASE_QUOTE",
            "Base quote fingerprint is stale or does not match the recalculated quote");
        long beforeLodging = amount(baseQuote.lodgingAmount(), "base lodging amount");
        require(beforeLodging > 0, "INVALID_OVERRIDE_TARGET",
            "A zero lodging quote cannot be reduced");
        require(draft.targetLodgingAmount() >= 0
                && draft.targetLodgingAmount() < beforeLodging,
            "INVALID_OVERRIDE_TARGET",
            "Target lodging amount must be a whole-VND reduction below the base lodging amount");
        validateAuthorization(authorization);

        boolean largeRequired = requiresLargeApproval(beforeLodging,
            draft.targetLodgingAmount());
        require(!largeRequired || authorization.largeApprovalScope() != null,
            "LARGE_OVERRIDE_APPROVAL_REQUIRED",
            "A lodging reduction greater than 20 percent requires large-override approval");
        require(largeRequired || authorization.largeApprovalScope() == null,
            "INVALID_OVERRIDE_AUTHORIZATION",
            "Large-override authorization evidence is allowed only when required");

        long beforeCommission = amountAllowZero(baseQuote.commissionAmount(),
            "base commission amount");
        long expectedBeforeCommission = roundBasisPoints(beforeLodging,
            baseQuote.commissionBps());
        require(beforeCommission == expectedBeforeCommission,
            "INVALID_STORED_PRICING_POLICY",
            "Base quote commission is inconsistent with its lodging amount");
        long beforeTotal = amountAllowZero(baseQuote.totalAmount(), "base total amount");
        require(beforeTotal == add(beforeLodging, beforeCommission),
            "INVALID_STORED_PRICING_POLICY",
            "Base quote total is inconsistent with lodging and commission");
        long afterCommission = roundBasisPoints(draft.targetLodgingAmount(),
            baseQuote.commissionBps());
        long afterTotal = add(draft.targetLodgingAmount(), afterCommission);
        long adjustment = subtract(draft.targetLodgingAmount(), beforeLodging);
        int reductionBps = reductionBasisPoints(beforeLodging, draft.targetLodgingAmount());
        return new Calculation(beforeLodging, adjustment, reductionBps, largeRequired,
            beforeCommission, afterCommission, beforeTotal, afterTotal);
    }

    private static void validateCommandMetadata(Draft draft) {
        require(draft != null && draft.quote() != null,
            "INVALID_OVERRIDE_INPUT", "Manual override input and base quote input are required");
        require(draft.idempotencyKey() != null
                && IDEMPOTENCY_KEY.matcher(draft.idempotencyKey()).matches(),
            "INVALID_IDEMPOTENCY_KEY", "Idempotency key must be 8-160 safe characters");
        require(draft.expectedBaseQuoteFingerprint() != null
                && FINGERPRINT.matcher(draft.expectedBaseQuoteFingerprint()).matches(),
            "INVALID_BASE_QUOTE_FINGERPRINT",
            "Expected base quote fingerprint must be lowercase SHA-256 hexadecimal");
        require(draft.reason() != null && draft.reason().equals(draft.reason().trim())
                && codePointLength(draft.reason()) >= 10
                && codePointLength(draft.reason()) <= 1000,
            "INVALID_OVERRIDE_REASON", "Override reason must contain 10-1000 trimmed characters");
        require(draft.correlationId() != null
                && draft.correlationId().equals(draft.correlationId().trim())
                && codePointLength(draft.correlationId()) >= 1
                && codePointLength(draft.correlationId()) <= 100,
            "INVALID_OVERRIDE_CORRELATION",
            "Override correlation ID must contain 1-100 trimmed characters");
    }

    private static void validateAuthorization(AuthorizationEvidence authorization) {
        require(authorization.actorAccountId() != null && authorization.actorProfileId() != null
                && authorization.authorizationVersion() >= 1
                && validScope(authorization.overrideScope()),
            "INVALID_OVERRIDE_AUTHORIZATION",
            "Override actor and authorization scope evidence are required");
        if (authorization.largeApprovalScope() != null) {
            require(authorization.largeApprovalAuthorizationVersion() != null
                    && authorization.largeApprovalAuthorizationVersion() >= 1
                    && validScope(authorization.largeApprovalScope()),
                "INVALID_OVERRIDE_AUTHORIZATION",
                "Large-override authorization evidence is invalid");
        } else {
            require(authorization.largeApprovalAuthorizationVersion() == null,
                "INVALID_OVERRIDE_AUTHORIZATION",
                "Large-override authorization version requires matching scope evidence");
        }
    }

    private static boolean validScope(ScopeEvidence scope) {
        return scope != null && ("ALL".equals(scope.type()) && scope.partitionId() == null
            || "BRANCH".equals(scope.type()) && scope.partitionId() != null);
    }

    private AppliedOverride replay(CommandRow row, UUID actorAccountId, String requestHash) {
        if (!COMMAND_TYPE.equals(row.commandType())
            || !requestHash.equals(row.requestHash())
            || !actorAccountId.equals(row.actorAccountId())) {
            throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different override command");
        }
        if (row.resourceId() == null || !"SUCCEEDED".equals(row.status())) {
            throw new PricingPolicyException("IDEMPOTENCY_COMMAND_INCOMPLETE",
                "The previous override command has no durable result");
        }
        var result = require(row.resourceId());
        var evidence = result.manualOverride();
        if (!row.idempotencyKey().equals(evidence.idempotencyKey())
            || !requestHash.equals(evidence.requestHash())
            || !actorAccountId.equals(evidence.actorAdminAccountId())) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_OVERRIDE",
                "Idempotency command does not match its pricing override artifact");
        }
        return result;
    }

    private AppliedOverride replay(
        ArtifactCommandRow row,
        UUID actorAccountId,
        String requestHash
    ) {
        if (!requestHash.equals(row.requestHash())
            || !actorAccountId.equals(row.actorAccountId())) {
            throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different override command");
        }
        return require(row.resourceId());
    }

    private Optional<CommandRow> command(String key) {
        return jdbc.sql("""
                SELECT idempotency_key, command_type, actor_account_id,
                       request_hash, resource_id, status
                FROM command_idempotency WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query((rs, rowNumber) -> new CommandRow(
                rs.getString("idempotency_key"), rs.getString("command_type"),
                rs.getObject("actor_account_id", UUID.class),
                rs.getString("request_hash").trim(),
                rs.getObject("resource_id", UUID.class), rs.getString("status")))
            .optional();
    }

    private Optional<ArtifactCommandRow> artifact(String key) {
        return jdbc.sql("""
                SELECT id, actor_admin_account_id, request_hash
                FROM pricing_manual_overrides
                WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query((rs, rowNumber) -> new ArtifactCommandRow(
                rs.getObject("id", UUID.class),
                rs.getObject("actor_admin_account_id", UUID.class),
                rs.getString("request_hash").trim()))
            .optional();
    }

    private StoredOverride mapStored(ResultSet rs, int rowNumber) throws SQLException {
        var manualOverride = new ManualOverride(
            rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
            rs.getString("request_hash").trim(),
            rs.getObject("property_id", UUID.class),
            rs.getObject("rate_profile_version_id", UUID.class),
            rs.getString("base_quote_fingerprint").trim(),
            rs.getString("calculation_version"),
            text(rs.getLong("before_lodging_amount")),
            text(rs.getLong("target_lodging_amount")),
            text(rs.getLong("adjustment_amount")), rs.getInt("reduction_bps"),
            rs.getInt("commission_bps"), text(rs.getLong("before_commission_amount")),
            text(rs.getLong("after_commission_amount")),
            text(rs.getLong("before_total_amount")), text(rs.getLong("after_total_amount")),
            rs.getString("currency").trim(), rs.getBoolean("large_approval_required"),
            rs.getObject("actor_admin_account_id", UUID.class),
            rs.getObject("actor_admin_profile_id", UUID.class),
            rs.getLong("authorization_version"), rs.getString("override_permission_code"),
            rs.getString("override_scope_type"),
            rs.getObject("override_partition_id", UUID.class),
            nullableLong(rs, "large_approval_authorization_version"),
            rs.getString("large_approval_permission_code"),
            rs.getString("large_approval_scope_type"),
            rs.getObject("large_approval_partition_id", UUID.class),
            rs.getString("reason"), rs.getString("correlation_id"),
            rs.getInt("quote_ttl_minutes"),
            rs.getObject("created_at", OffsetDateTime.class).toString(),
            rs.getObject("expires_at", OffsetDateTime.class).toString()
        );
        return new StoredOverride(manualOverride, rs.getString("base_quote_snapshot"),
            rs.getString("applied_override_snapshot"));
    }

    private static Long nullableLong(ResultSet rs, String field) throws SQLException {
        var value = rs.getObject(field);
        return value == null ? null : ((Number) value).longValue();
    }

    private AppliedOverride applied(Quote baseQuote, ManualOverride manualOverride) {
        var components = new ArrayList<PriceComponent>();
        baseQuote.components().stream()
            .filter(component -> component.type() != PriceComponentType.COMMISSION)
            .forEach(components::add);
        int nextOrder = components.stream().mapToInt(PriceComponent::order).max().orElse(0) + 1;
        components.add(new PriceComponent(nextOrder++, PriceComponentType.MANUAL_OVERRIDE,
            "manual-override", null, null, 1, manualOverride.beforeLodgingAmount(),
            manualOverride.reductionBps(), manualOverride.adjustmentAmount()));
        if (!"0".equals(manualOverride.afterCommissionAmount())) {
            components.add(new PriceComponent(nextOrder, PriceComponentType.COMMISSION,
                "staff-commission", null, null, 1, manualOverride.targetLodgingAmount(),
                manualOverride.commissionBps(), manualOverride.afterCommissionAmount()));
        }
        var precedence = new ArrayList<>(baseQuote.precedence());
        precedence.remove(BASE_COMMISSION_PRECEDENCE);
        precedence.add(OVERRIDE_PRECEDENCE);
        precedence.add(FINAL_COMMISSION_PRECEDENCE);
        return new AppliedOverride(baseQuote, manualOverride, List.copyOf(precedence),
            List.copyOf(components), manualOverride.targetLodgingAmount(),
            manualOverride.afterCommissionAmount(), manualOverride.afterTotalAmount());
    }

    private Map<String, Object> quoteInputSnapshot(Command command) {
        var input = new LinkedHashMap<String, Object>();
        input.put("rateProfileVersionId", command.rateProfileVersionId().toString());
        input.put("propertyId", command.propertyId().toString());
        input.put("checkInDate", command.checkInDate().toString());
        input.put("checkOutDate", command.checkOutDate().toString());
        input.put("guestCount", command.guestCount());
        input.put("channel", command.channel());
        input.put("staffCreated", command.staffCreated());
        input.put("commissionBps", command.commissionBps());
        return input;
    }

    private String requestHash(Draft draft) {
        try {
            var canonical = new StringBuilder();
            append(canonical, COMMAND_TYPE);
            append(canonical, draft.idempotencyKey());
            append(canonical, draft.quote().rateProfileVersionId());
            append(canonical, draft.quote().propertyId());
            append(canonical, draft.quote().checkInDate());
            append(canonical, draft.quote().checkOutDate());
            append(canonical, draft.quote().guestCount());
            append(canonical, draft.quote().channel());
            append(canonical, draft.quote().staffCreated());
            append(canonical, draft.quote().commissionBps());
            append(canonical, draft.expectedBaseQuoteFingerprint());
            append(canonical, draft.targetLodgingAmount());
            append(canonical, draft.reason());
            append(canonical, draft.correlationId());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, Object value) {
        var text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append(';');
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cannot serialize pricing override evidence", failure);
        }
    }

    private Quote readQuote(String value) {
        try {
            return json.readValue(value, Quote.class);
        } catch (JacksonException failure) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_OVERRIDE",
                "Stored base quote snapshot is invalid");
        }
    }

    private AppliedSnapshot readAppliedSnapshot(String value) {
        try {
            return json.readValue(value, AppliedSnapshot.class);
        } catch (RuntimeException failure) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_OVERRIDE",
                "Stored applied override snapshot is invalid");
        }
    }

    private static long amount(String value, String field) {
        long amount = amountAllowZero(value, field);
        require(amount > 0, "INVALID_STORED_PRICING_POLICY", field + " must be positive");
        return amount;
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

    private static int reductionBasisPoints(long before, long target) {
        var numerator = BigInteger.valueOf(before - target).multiply(BigInteger.valueOf(10_000));
        var rounded = numerator.add(BigInteger.valueOf(before).divide(BigInteger.TWO))
            .divide(BigInteger.valueOf(before));
        return rounded.intValueExact();
    }

    private static long roundBasisPoints(long amount, int basisPoints) {
        require(amount >= 0 && basisPoints >= 0,
            "INVALID_STORED_PRICING_POLICY", "Pricing amounts and factors must not be negative");
        return exactLong(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(basisPoints))
            .add(BigInteger.valueOf(5_000)).divide(BigInteger.valueOf(10_000)));
    }

    private static long add(long left, long right) {
        return exactLong(BigInteger.valueOf(left).add(BigInteger.valueOf(right)));
    }

    private static long subtract(long left, long right) {
        return exactLong(BigInteger.valueOf(left).subtract(BigInteger.valueOf(right)));
    }

    private static long exactLong(BigInteger value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new PricingPolicyException("PRICE_OVERFLOW",
                "Calculated pricing exceeds VND limits");
        }
    }

    private static String text(long value) {
        return Long.toString(value);
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new PricingPolicyException(code, message);
    }

    private record Calculation(
        long beforeLodging,
        long adjustment,
        int reductionBps,
        boolean largeApprovalRequired,
        long beforeCommission,
        long afterCommission,
        long beforeTotal,
        long afterTotal
    ) {}

    private record CommandRow(
        String idempotencyKey,
        String commandType,
        UUID actorAccountId,
        String requestHash,
        UUID resourceId,
        String status
    ) {}

    private record ArtifactCommandRow(
        UUID resourceId,
        UUID actorAccountId,
        String requestHash
    ) {}

    private record StoredOverride(
        ManualOverride manualOverride,
        String baseQuoteSnapshot,
        String appliedOverrideSnapshot
    ) {}

    private record AppliedSnapshot(
        ManualOverride manualOverride,
        List<String> finalPrecedence,
        List<PriceComponent> finalComponents,
        String finalLodgingAmount,
        String finalCommissionAmount,
        String finalTotalAmount
    ) {}

    public record Draft(
        String idempotencyKey,
        Command quote,
        String expectedBaseQuoteFingerprint,
        long targetLodgingAmount,
        String reason,
        String correlationId
    ) {}

    public record AuthorizationEvidence(
        UUID actorAccountId,
        UUID actorProfileId,
        long authorizationVersion,
        ScopeEvidence overrideScope,
        Long largeApprovalAuthorizationVersion,
        ScopeEvidence largeApprovalScope
    ) {}

    public record ManualOverride(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID propertyId,
        UUID rateProfileVersionId,
        String baseQuoteFingerprint,
        String calculationVersion,
        String beforeLodgingAmount,
        String targetLodgingAmount,
        String adjustmentAmount,
        int reductionBps,
        int commissionBps,
        String beforeCommissionAmount,
        String afterCommissionAmount,
        String beforeTotalAmount,
        String afterTotalAmount,
        String currency,
        boolean largeApprovalRequired,
        UUID actorAdminAccountId,
        UUID actorAdminProfileId,
        long authorizationVersion,
        String overridePermissionCode,
        String overrideScopeType,
        UUID overridePartitionId,
        Long largeApprovalAuthorizationVersion,
        String largeApprovalPermissionCode,
        String largeApprovalScopeType,
        UUID largeApprovalPartitionId,
        String reason,
        String correlationId,
        int quoteTtlMinutes,
        String createdAt,
        String expiresAt
    ) {}

    public record AppliedOverride(
        Quote baseQuote,
        ManualOverride manualOverride,
        List<String> finalPrecedence,
        List<PriceComponent> finalComponents,
        String finalLodgingAmount,
        String finalCommissionAmount,
        String finalTotalAmount
    ) {}
}
