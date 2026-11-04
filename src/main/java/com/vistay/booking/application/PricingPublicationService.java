package com.vistay.booking.application;

import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class PricingPublicationService {
    private static final String COMMAND_TYPE = "PUBLISH_PROPERTY_RATE_PROFILE_VERSION";
    private static final String PERMISSION = "pricing:approve";
    private static final String SNAPSHOT_SCHEMA_VERSION = "PRICING_PUBLICATION_V1";
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("^[A-Za-z0-9._:-]{8,160}$");
    private static final Pattern FINGERPRINT = Pattern.compile("^[0-9a-f]{64}$");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final PricingPolicyService pricing;
    private final Clock clock;

    public PricingPublicationService(
        JdbcClient jdbc,
        ObjectMapper json,
        PricingPolicyService pricing,
        Clock clock
    ) {
        this.jdbc = jdbc;
        this.json = json;
        this.pricing = pricing;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PublicationPreview preview(UUID profileVersionId) {
        var profile = pricing.requireVersion(profileVersionId);
        return new PublicationPreview(profile, profileFingerprint(profile),
            latestForProperty(profile.propertyId()).orElse(null),
            publicationForProfile(profileVersionId).orElse(null));
    }

    @Transactional
    public Publication publish(
        Draft draft,
        ProfileVersion profile,
        AuthorizationEvidence authorization
    ) {
        validate(draft, profile, authorization);
        var requestHash = requestHash(draft);
        var durable = publicationForKey(draft.idempotencyKey());
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

        acquirePropertyLock(profile.propertyId());
        var current = latestForProperty(profile.propertyId()).orElse(null);
        var currentId = current == null ? null : current.id();
        require(Objects.equals(draft.expectedCurrentPublicationId(), currentId),
            "STALE_PRICING_PUBLICATION_HEAD",
            "The property's latest pricing publication changed after preview");
        if (publicationForProfile(profile.id()).isPresent()) {
            throw new PricingPolicyException("PRICING_VERSION_ALREADY_PUBLISHED",
                "Pricing profile version has already been published by another command");
        }

        var id = UUID.randomUUID();
        long publicationRevision = current == null ? 1 : current.publicationRevision() + 1;
        var publishedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
        jdbc.sql("""
                INSERT INTO pricing_profile_publications(
                    id, idempotency_key, request_hash, property_id,
                    rate_profile_version_id, previous_publication_id,
                    publication_revision, profile_fingerprint,
                    snapshot_schema_version, profile_snapshot,
                    actor_admin_account_id, actor_admin_profile_id,
                    authorization_version, permission_code, scope_type, partition_id,
                    reason, correlation_id, published_at
                ) VALUES (
                    :id, :key, :requestHash, :propertyId, :profileId,
                    :previousPublicationId, :publicationRevision,
                    :fingerprint, :snapshotSchemaVersion,
                    CAST(:profileSnapshot AS jsonb), :actorAccountId,
                    :actorProfileId, :authorizationVersion, :permissionCode,
                    :scopeType, :partitionId, :reason, :correlationId, :publishedAt
                )
                """)
            .param("id", id)
            .param("key", draft.idempotencyKey())
            .param("requestHash", requestHash)
            .param("propertyId", profile.propertyId())
            .param("profileId", profile.id())
            .param("previousPublicationId", currentId)
            .param("publicationRevision", publicationRevision)
            .param("fingerprint", draft.expectedProfileFingerprint())
            .param("snapshotSchemaVersion", SNAPSHOT_SCHEMA_VERSION)
            .param("profileSnapshot", writeJson(
                new PublicationSnapshot(SNAPSHOT_SCHEMA_VERSION, profile)))
            .param("actorAccountId", authorization.actorAccountId())
            .param("actorProfileId", authorization.actorProfileId())
            .param("authorizationVersion", authorization.authorizationVersion())
            .param("permissionCode", PERMISSION)
            .param("scopeType", authorization.scope().type())
            .param("partitionId", authorization.scope().partitionId())
            .param("reason", draft.reason())
            .param("correlationId", draft.correlationId())
            .param("publishedAt", publishedAt)
            .update();

        var changes = new LinkedHashMap<String, Object>();
        changes.put("propertyId", profile.propertyId().toString());
        changes.put("rateProfileVersionId", profile.id().toString());
        changes.put("profileVersion", profile.version());
        changes.put("previousPublicationId", currentId);
        changes.put("publicationRevision", publicationRevision);
        changes.put("profileFingerprint", draft.expectedProfileFingerprint());
        changes.put("authorizationVersion", authorization.authorizationVersion());
        changes.put("permissionCode", PERMISSION);
        changes.put("scopeType", authorization.scope().type());
        changes.put("partitionId", authorization.scope().partitionId());
        changes.put("reason", draft.reason());
        jdbc.sql("""
                INSERT INTO audit_events(
                    actor_account_type, actor_account_id, action, aggregate_type,
                    aggregate_id, changes, correlation_id, created_at
                ) VALUES (
                    'ADMIN', :actorId, 'PRICING_PROFILE_PUBLISHED',
                    'PricingProfilePublication', :publicationId,
                    CAST(:changes AS jsonb), :correlationId, :publishedAt
                )
                """)
            .param("actorId", authorization.actorAccountId())
            .param("publicationId", id)
            .param("changes", writeJson(changes))
            .param("correlationId", draft.correlationId())
            .param("publishedAt", publishedAt)
            .update();

        var event = new LinkedHashMap<String, Object>();
        event.put("publicationId", id.toString());
        event.put("propertyId", profile.propertyId().toString());
        event.put("rateProfileVersionId", profile.id().toString());
        event.put("previousPublicationId", currentId == null ? null : currentId.toString());
        event.put("publicationRevision", publicationRevision);
        event.put("profileVersion", profile.version());
        event.put("snapshotSchemaVersion", SNAPSHOT_SCHEMA_VERSION);
        event.put("profileFingerprint", draft.expectedProfileFingerprint());
        event.put("effectiveFrom", profile.effectiveFrom());
        event.put("effectiveToExclusive", profile.effectiveToExclusive());
        event.put("publishedAt", publishedAt.toString());
        jdbc.sql("""
                INSERT INTO outbox_events(
                    aggregate_type, aggregate_id, aggregate_version, event_type,
                    event_version, payload, correlation_id, occurred_at
                ) VALUES (
                    'PropertyPricingPublication', :propertyId, :publicationRevision,
                    'vistay.booking.rate-profile-published', 1,
                    CAST(:payload AS jsonb), :correlationId, :publishedAt
                )
                """)
            .param("propertyId", profile.propertyId())
            .param("publicationRevision", publicationRevision)
            .param("payload", writeJson(event))
            .param("correlationId", draft.correlationId())
            .param("publishedAt", publishedAt)
            .update();

        jdbc.sql("""
                UPDATE command_idempotency
                SET resource_type = 'PricingProfilePublication', resource_id = :resourceId,
                    response_code = 200, response_body = CAST(:response AS jsonb),
                    status = 'SUCCEEDED', completed_at = now()
                WHERE idempotency_key = :key
                """)
            .param("resourceId", id)
            .param("response", writeJson(Map.of("publicationId", id.toString())))
            .param("key", draft.idempotencyKey())
            .update();
        return require(id);
    }

    @Transactional(readOnly = true)
    public Publication require(UUID id) {
        var stored = jdbc.sql("""
                SELECT id, idempotency_key, request_hash, property_id,
                       rate_profile_version_id, previous_publication_id,
                       publication_revision, profile_fingerprint,
                       snapshot_schema_version,
                       profile_snapshot::text AS profile_snapshot,
                       actor_admin_account_id, actor_admin_profile_id,
                       authorization_version, permission_code, scope_type, partition_id,
                       reason, correlation_id, published_at
                FROM pricing_profile_publications WHERE id = :id
                """)
            .param("id", id)
            .query(this::mapStored)
            .optional()
            .orElseThrow(() -> new PricingPolicyException("PRICING_PUBLICATION_NOT_FOUND",
                "Pricing profile publication was not found"));
        var profile = pricing.requireVersion(stored.rateProfileVersionId());
        if (!profile.propertyId().equals(stored.propertyId())
            || !SNAPSHOT_SCHEMA_VERSION.equals(stored.snapshotSchemaVersion())
            || !profileFingerprint(profile).equals(stored.profileFingerprint())) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_PUBLICATION",
                "Stored pricing publication does not match its immutable profile snapshot");
        }
        var snapshot = readSnapshot(stored.profileSnapshot());
        if (!SNAPSHOT_SCHEMA_VERSION.equals(snapshot.schemaVersion())
            || !profile.equals(snapshot.profile())) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_PUBLICATION",
                "Stored pricing publication snapshot is invalid");
        }
        return stored.toPublication(snapshot.profile());
    }

    @Transactional(readOnly = true)
    public ActiveProfile active(UUID propertyId, LocalDate checkIn, LocalDate checkOut) {
        require(propertyId != null && checkIn != null && checkOut != null
                && checkOut.isAfter(checkIn),
            "INVALID_STAY_RANGE", "Checkout must be after check-in");
        var publicationId = jdbc.sql("""
                SELECT publication.id
                FROM pricing_profile_publications publication
                JOIN property_rate_profile_versions profile
                  ON profile.id = publication.rate_profile_version_id
                WHERE publication.property_id = :propertyId
                  AND profile.effective_from <= :checkIn
                  AND (profile.effective_to_exclusive IS NULL
                       OR profile.effective_to_exclusive >= :checkOut)
                ORDER BY profile.effective_from DESC, profile.version DESC,
                         publication.published_at DESC, publication.id DESC
                LIMIT 1
                """)
            .param("propertyId", propertyId)
            .param("checkIn", checkIn)
            .param("checkOut", checkOut)
            .query(UUID.class)
            .optional()
            .orElseThrow(() -> new PricingPolicyException("ACTIVE_PRICING_NOT_FOUND",
                "No published pricing profile covers the complete stay"));
        var publication = require(publicationId);
        return new ActiveProfile(publication, publication.profile());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PublicationHistory reconcile(
        UUID propertyId,
        long afterPublicationRevision,
        int pageSize
    ) {
        require(propertyId != null, "INVALID_RECONCILIATION_INPUT",
            "Property ID is required for pricing publication reconciliation");
        require(afterPublicationRevision >= 0, "INVALID_RECONCILIATION_INPUT",
            "After-publication revision must not be negative");
        require(pageSize >= 1 && pageSize <= 100, "INVALID_RECONCILIATION_INPUT",
            "Pricing publication reconciliation page size must be between 1 and 100");

        var head = latestForProperty(propertyId).orElse(null);
        var publicationIds = jdbc.sql("""
                SELECT id
                FROM pricing_profile_publications
                WHERE property_id = :propertyId
                  AND publication_revision > :afterRevision
                ORDER BY publication_revision ASC
                LIMIT :fetchLimit
                """)
            .param("propertyId", propertyId)
            .param("afterRevision", afterPublicationRevision)
            .param("fetchLimit", pageSize + 1)
            .query(UUID.class)
            .list();
        boolean hasMore = publicationIds.size() > pageSize;
        List<Publication> publications = publicationIds.stream()
            .limit(pageSize)
            .map(this::require)
            .toList();
        long nextRevision = publications.isEmpty()
            ? afterPublicationRevision
            : publications.getLast().publicationRevision();
        return new PublicationHistory(propertyId, head, publications, nextRevision, hasMore);
    }

    public String profileFingerprint(ProfileVersion profile) {
        require(profile != null, "PRICING_VERSION_NOT_FOUND", "Pricing profile is required");
        var canonical = new StringBuilder();
        append(canonical, SNAPSHOT_SCHEMA_VERSION);
        append(canonical, profile.id());
        append(canonical, profile.propertyId());
        append(canonical, profile.version());
        append(canonical, profile.effectiveFrom());
        append(canonical, profile.effectiveToExclusive());
        append(canonical, profile.baseDailyRate());
        append(canonical, profile.mediumDailyFactorBps());
        append(canonical, profile.longDailyFactorBps());
        append(canonical, profile.currency());
        append(canonical, profile.checkInTime());
        append(canonical, profile.checkOutTime());
        append(canonical, profile.turnoverMinutes());
        append(canonical, profile.quoteTtlMinutes());
        append(canonical, profile.commissionDefaultBps());
        append(canonical, profile.commissionMaxBps());
        append(canonical, profile.reason());
        append(canonical, profile.authoredByAdminAccountId());
        append(canonical, profile.correlationId());
        append(canonical, profile.authoredAt());
        profile.rules().stream()
            .sorted(Comparator.comparingInt(PricingPolicyService.RuleVersion::priority)
                .thenComparing(PricingPolicyService.RuleVersion::code)
                .thenComparing(rule -> rule.id().toString()))
            .forEach(rule -> {
                append(canonical, rule.id());
                append(canonical, rule.code());
                append(canonical, rule.name());
                append(canonical, rule.effectiveFrom());
                append(canonical, rule.effectiveToExclusive());
                append(canonical, rule.priority());
                append(canonical, rule.daysOfWeek());
                append(canonical, rule.channels());
                append(canonical, rule.minimumStayDays());
                append(canonical, rule.maximumStayDays());
                append(canonical, rule.minimumGuests());
                append(canonical, rule.maximumGuests());
                append(canonical, rule.actionType());
                append(canonical, rule.actionValue());
            });
        return sha256(canonical.toString());
    }

    private void validate(
        Draft draft,
        ProfileVersion profile,
        AuthorizationEvidence authorization
    ) {
        require(draft != null && profile != null && authorization != null,
            "INVALID_PUBLICATION_INPUT", "Publication input and authorization are required");
        require(draft.idempotencyKey() != null
                && IDEMPOTENCY_KEY.matcher(draft.idempotencyKey()).matches(),
            "INVALID_IDEMPOTENCY_KEY", "Idempotency key has an invalid format");
        require(draft.profileVersionId() != null
                && draft.profileVersionId().equals(profile.id()),
            "PRICING_VERSION_MISMATCH", "Publication profile does not match the loaded version");
        require(draft.expectedProfileFingerprint() != null
                && FINGERPRINT.matcher(draft.expectedProfileFingerprint()).matches(),
            "INVALID_PROFILE_FINGERPRINT", "Expected profile fingerprint is invalid");
        require(draft.expectedProfileFingerprint().equals(profileFingerprint(profile)),
            "STALE_PRICING_PROFILE", "Pricing profile fingerprint is stale");
        require(trimmedLength(draft.reason(), 10, 1000),
            "INVALID_PUBLICATION_REASON", "Publication reason must contain 10 to 1000 characters");
        require(isCanonicalUuid(draft.correlationId()),
            "INVALID_CORRELATION_ID", "Correlation ID must be a canonical UUID");
        require(authorization.actorAccountId() != null
                && authorization.actorProfileId() != null
                && authorization.authorizationVersion() >= 1
                && validScope(authorization.scope()),
            "INVALID_PUBLICATION_AUTHORIZATION", "Publication authorization evidence is invalid");
    }

    private Publication replay(CommandRow row, UUID actorId, String requestHash) {
        if (!COMMAND_TYPE.equals(row.commandType())
            || !actorId.equals(row.actorAccountId())
            || !requestHash.equals(row.requestHash())) {
            throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different pricing command");
        }
        if (!"SUCCEEDED".equals(row.status()) || row.resourceId() == null) {
            throw new PricingPolicyException("IDEMPOTENCY_COMMAND_INCOMPLETE",
                "The previous pricing publication has no durable result");
        }
        return require(row.resourceId());
    }

    private Publication replay(StoredPublication stored, UUID actorId, String requestHash) {
        if (!actorId.equals(stored.actorAdminAccountId())
            || !requestHash.equals(stored.requestHash())) {
            throw new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
                "Idempotency key was already used for a different pricing command");
        }
        return require(stored.id());
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

    private Optional<StoredPublication> publicationForKey(String key) {
        return jdbc.sql("""
                SELECT id, idempotency_key, request_hash, property_id,
                       rate_profile_version_id, previous_publication_id,
                       publication_revision, profile_fingerprint,
                       snapshot_schema_version,
                       profile_snapshot::text AS profile_snapshot,
                       actor_admin_account_id, actor_admin_profile_id,
                       authorization_version, permission_code, scope_type, partition_id,
                       reason, correlation_id, published_at
                FROM pricing_profile_publications WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query(this::mapStored)
            .optional();
    }

    private Optional<Publication> publicationForProfile(UUID profileVersionId) {
        return jdbc.sql("""
                SELECT id FROM pricing_profile_publications
                WHERE rate_profile_version_id = :profileId
                """)
            .param("profileId", profileVersionId)
            .query(UUID.class)
            .optional()
            .map(this::require);
    }

    private Optional<Publication> latestForProperty(UUID propertyId) {
        return jdbc.sql("""
                SELECT publication.id
                FROM pricing_profile_publications publication
                JOIN property_rate_profile_versions profile
                  ON profile.id = publication.rate_profile_version_id
                WHERE publication.property_id = :propertyId
                ORDER BY publication.publication_revision DESC
                LIMIT 1
                """)
            .param("propertyId", propertyId)
            .query(UUID.class)
            .optional()
            .map(this::require);
    }

    private StoredPublication mapStored(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredPublication(
            rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
            rs.getString("request_hash").trim(), rs.getObject("property_id", UUID.class),
            rs.getObject("rate_profile_version_id", UUID.class),
            rs.getObject("previous_publication_id", UUID.class),
            rs.getLong("publication_revision"),
            rs.getString("profile_fingerprint").trim(),
            rs.getString("snapshot_schema_version"), rs.getString("profile_snapshot"),
            rs.getObject("actor_admin_account_id", UUID.class),
            rs.getObject("actor_admin_profile_id", UUID.class),
            rs.getLong("authorization_version"), rs.getString("permission_code"),
            rs.getString("scope_type"), rs.getObject("partition_id", UUID.class),
            rs.getString("reason"), rs.getString("correlation_id"),
            rs.getObject("published_at", OffsetDateTime.class).toString()
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
        append(canonical, draft.profileVersionId());
        append(canonical, draft.expectedProfileFingerprint());
        append(canonical, draft.expectedCurrentPublicationId());
        append(canonical, draft.reason());
        append(canonical, draft.correlationId());
        return sha256(canonical.toString());
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cannot serialize pricing publication evidence", failure);
        }
    }

    private PublicationSnapshot readSnapshot(String value) {
        try {
            return json.readValue(value, PublicationSnapshot.class);
        } catch (RuntimeException failure) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_PUBLICATION",
                "Stored pricing profile snapshot is invalid");
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
        target.append(text.getBytes(StandardCharsets.UTF_8).length)
            .append(':').append(text).append(';');
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

    private record StoredPublication(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID propertyId,
        UUID rateProfileVersionId,
        UUID previousPublicationId,
        long publicationRevision,
        String profileFingerprint,
        String snapshotSchemaVersion,
        String profileSnapshot,
        UUID actorAdminAccountId,
        UUID actorAdminProfileId,
        long authorizationVersion,
        String permissionCode,
        String scopeType,
        UUID partitionId,
        String reason,
        String correlationId,
        String publishedAt
    ) {
        Publication toPublication(ProfileVersion profile) {
            return new Publication(id, idempotencyKey, requestHash, propertyId,
                rateProfileVersionId, previousPublicationId, publicationRevision,
                profileFingerprint, snapshotSchemaVersion,
                actorAdminAccountId,
                actorAdminProfileId, authorizationVersion, permissionCode, scopeType,
                partitionId, reason, correlationId, publishedAt, profile);
        }
    }

    public record Draft(
        String idempotencyKey,
        UUID profileVersionId,
        String expectedProfileFingerprint,
        UUID expectedCurrentPublicationId,
        String reason,
        String correlationId
    ) {}

    public record AuthorizationEvidence(
        UUID actorAccountId,
        UUID actorProfileId,
        long authorizationVersion,
        ScopeEvidence scope
    ) {}

    public record PublicationPreview(
        ProfileVersion profile,
        String profileFingerprint,
        Publication currentPublication,
        Publication publication
    ) {
        public boolean published() { return publication != null; }
    }

    public record Publication(
        UUID id,
        String idempotencyKey,
        String requestHash,
        UUID propertyId,
        UUID rateProfileVersionId,
        UUID previousPublicationId,
        long publicationRevision,
        String profileFingerprint,
        String snapshotSchemaVersion,
        UUID actorAdminAccountId,
        UUID actorAdminProfileId,
        long authorizationVersion,
        String permissionCode,
        String scopeType,
        UUID partitionId,
        String reason,
        String correlationId,
        String publishedAt,
        ProfileVersion profile
    ) {}

    public record ActiveProfile(Publication publication, ProfileVersion profile) {}

    public record PublicationHistory(
        UUID propertyId,
        Publication currentHead,
        List<Publication> publications,
        long nextAfterPublicationRevision,
        boolean hasMore
    ) {
        public PublicationHistory {
            publications = List.copyOf(publications);
        }
    }

    private record PublicationSnapshot(String schemaVersion, ProfileVersion profile) {}
}
