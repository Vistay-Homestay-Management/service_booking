package com.vistay.booking.application;

import com.vistay.booking.application.InventoryScopePolicy.ScopeEvidence;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class InventoryBlockCommandService {
    static final String CREATE_COMMAND = "CREATE_INVENTORY_BLOCK";
    static final String RELEASE_COMMAND = "RELEASE_INVENTORY_BLOCK";
    static final String PERMISSION = "inventory:block";
    static final String EVENT_TYPE = "vistay.booking.inventory-block-changed";
    private static final Set<String> OPERATOR_TYPES = Set.of(
        "MAINTENANCE", "CLEANING", "OWNER_USE", "MANUAL");
    private static final Pattern IDEMPOTENCY_KEY =
        Pattern.compile("^[A-Za-z0-9._:-]{8,160}$");

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public InventoryBlockCommandService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public CommandResult create(CreateDraft draft, AuthorizationEvidence authorization) {
        validateCreate(draft, authorization);
        var requestHash = createRequestHash(draft);
        try {
            var durable = commandForKey(draft.idempotencyKey());
            if (durable.isPresent()) {
                return replay(durable.get(), CREATE_COMMAND,
                    authorization.actorAccountId(), requestHash);
            }
            if (!reserve(draft.idempotencyKey(), CREATE_COMMAND,
                authorization.actorAccountId(), requestHash)) {
                return replayGeneric(draft.idempotencyKey(), CREATE_COMMAND,
                    authorization.actorAccountId(), requestHash);
            }

            acquirePropertyLock(draft.propertyId());
            requireExpectedRevision(draft.propertyId(), draft.expectedInventoryRevision());

            var commandId = UUID.randomUUID();
            var blockId = UUID.randomUUID();
            var occurredAt = now();
            jdbc.sql("""
                    INSERT INTO inventory_blocks(
                        id, property_id, block_type, status, start_date,
                        end_date_exclusive, reason, created_by_admin_user_id,
                        version, created_at, updated_at
                    ) VALUES (
                        :id, :propertyId, :blockType, 'ACTIVE', :startDate,
                        :endDate, :reason, :actorProfileId, 0, :occurredAt, :occurredAt
                    )
                    """)
                .param("id", blockId)
                .param("propertyId", draft.propertyId())
                .param("blockType", draft.blockType())
                .param("startDate", draft.startDate())
                .param("endDate", draft.endDateExclusive())
                .param("reason", draft.reason())
                .param("actorProfileId", authorization.actorProfileId())
                .param("occurredAt", occurredAt)
                .update();

            var revision = requireResultingRevision(
                draft.propertyId(), draft.expectedInventoryRevision());
            insertOutcome(commandId, draft.idempotencyKey(), requestHash, CREATE_COMMAND,
                blockId, draft.propertyId(), draft.blockType(), "ACTIVE",
                draft.startDate(), draft.endDateExclusive(),
                draft.expectedInventoryRevision(), revision, 0,
                authorization, draft.reason(), draft.correlationId(), occurredAt, null);
            writeAuditAndOutbox(commandId, blockId, draft.propertyId(), "CREATED",
                draft.blockType(), null, "ACTIVE", draft.startDate(),
                draft.endDateExclusive(), 0, revision, authorization,
                draft.reason(), draft.correlationId(), occurredAt);
            completeReservation(draft.idempotencyKey(), commandId);
            return requireCommand(commandId);
        } catch (PricingPolicyException expected) {
            throw expected;
        } catch (DataIntegrityViolationException conflict) {
            if (hasSqlState(conflict, "23P01")) {
                throw new PricingPolicyException("INVENTORY_BLOCK_CONFLICT",
                    "The requested date range conflicts with active inventory");
            }
            throw commandFailed();
        } catch (DataAccessException failure) {
            throw commandFailed();
        }
    }

    @Transactional
    public CommandResult release(ReleaseDraft draft, AuthorizationEvidence authorization) {
        validateRelease(draft, authorization);
        var requestHash = releaseRequestHash(draft);
        try {
            var durable = commandForKey(draft.idempotencyKey());
            if (durable.isPresent()) {
                return replay(durable.get(), RELEASE_COMMAND,
                    authorization.actorAccountId(), requestHash);
            }
            if (!reserve(draft.idempotencyKey(), RELEASE_COMMAND,
                authorization.actorAccountId(), requestHash)) {
                return replayGeneric(draft.idempotencyKey(), RELEASE_COMMAND,
                    authorization.actorAccountId(), requestHash);
            }

            acquirePropertyLock(draft.propertyId());
            var block = lockBlock(draft.inventoryBlockId(), draft.propertyId());
            requireExpectedRevision(draft.propertyId(), draft.expectedInventoryRevision());
            if (!OPERATOR_TYPES.contains(block.blockType())) {
                throw new PricingPolicyException("INVENTORY_BLOCK_TYPE_FORBIDDEN",
                    "This inventory block type is controlled by its owning workflow");
            }
            if ("RELEASED".equals(block.status())) {
                throw new PricingPolicyException("INVENTORY_BLOCK_ALREADY_RELEASED",
                    "Inventory block is already released");
            }
            if (!Set.of("TENTATIVE", "ACTIVE").contains(block.status())) {
                throw new PricingPolicyException("INVENTORY_BLOCK_NOT_RELEASABLE",
                    "Inventory block is not in a releasable state");
            }

            var commandId = UUID.randomUUID();
            var occurredAt = now();
            var newBlockVersion = block.version() + 1;
            int updated = jdbc.sql("""
                    UPDATE inventory_blocks
                    SET status = 'RELEASED', released_at = :releasedAt,
                        version = :newVersion, updated_at = :releasedAt
                    WHERE id = :id AND property_id = :propertyId
                      AND version = :oldVersion
                      AND status IN ('TENTATIVE', 'ACTIVE')
                    """)
                .param("releasedAt", occurredAt)
                .param("newVersion", newBlockVersion)
                .param("id", block.id())
                .param("propertyId", block.propertyId())
                .param("oldVersion", block.version())
                .update();
            if (updated != 1) {
                throw new PricingPolicyException("INVENTORY_BLOCK_NOT_RELEASABLE",
                    "Inventory block changed before it could be released");
            }

            var revision = requireResultingRevision(
                draft.propertyId(), draft.expectedInventoryRevision());
            insertOutcome(commandId, draft.idempotencyKey(), requestHash, RELEASE_COMMAND,
                block.id(), block.propertyId(), block.blockType(), "RELEASED",
                block.startDate(), block.endDateExclusive(),
                draft.expectedInventoryRevision(), revision, newBlockVersion,
                authorization, draft.reason(), draft.correlationId(), occurredAt, occurredAt);
            writeAuditAndOutbox(commandId, block.id(), block.propertyId(), "RELEASED",
                block.blockType(), block.status(), "RELEASED", block.startDate(),
                block.endDateExclusive(), newBlockVersion, revision, authorization,
                draft.reason(), draft.correlationId(), occurredAt);
            completeReservation(draft.idempotencyKey(), commandId);
            return requireCommand(commandId);
        } catch (PricingPolicyException expected) {
            throw expected;
        } catch (DataAccessException failure) {
            throw commandFailed();
        }
    }

    private boolean reserve(
        String idempotencyKey,
        String commandType,
        UUID actorAccountId,
        String requestHash
    ) {
        return jdbc.sql("""
                INSERT INTO command_idempotency(
                    idempotency_key, command_type, actor_account_type,
                    actor_account_id, request_hash, status, expires_at
                ) VALUES (
                    :key, :commandType, 'ADMIN', :actorId, :requestHash,
                    'PROCESSING', now() + interval '30 days'
                )
                ON CONFLICT (idempotency_key) DO NOTHING
                """)
            .param("key", idempotencyKey)
            .param("commandType", commandType)
            .param("actorId", actorAccountId)
            .param("requestHash", requestHash)
            .update() == 1;
    }

    private CommandResult replayGeneric(
        String key,
        String commandType,
        UUID actorAccountId,
        String requestHash
    ) {
        var command = genericCommand(key).orElseThrow(() ->
            new PricingPolicyException("IDEMPOTENCY_COMMAND_INCOMPLETE",
                "The idempotent inventory command has no durable result"));
        if (!commandType.equals(command.commandType())
            || !actorAccountId.equals(command.actorAccountId())
            || !requestHash.equals(command.requestHash())) {
            throw idempotencyReused();
        }
        if (!"SUCCEEDED".equals(command.status()) || command.resourceId() == null) {
            throw new PricingPolicyException("IDEMPOTENCY_COMMAND_INCOMPLETE",
                "The idempotent inventory command has no durable result");
        }
        return requireCommand(command.resourceId());
    }

    private CommandResult replay(
        StoredCommand stored,
        String commandType,
        UUID actorAccountId,
        String requestHash
    ) {
        if (!commandType.equals(stored.commandType())
            || !actorAccountId.equals(stored.actorAdminAccountId())
            || !requestHash.equals(stored.requestHash())) {
            throw idempotencyReused();
        }
        return stored.toResult();
    }

    private void acquirePropertyLock(UUID propertyId) {
        jdbc.sql("""
                SELECT hashtextextended('inventory:' || CAST(:propertyId AS text), 0)
                FROM (SELECT pg_advisory_xact_lock(hashtextextended(
                    'inventory:' || CAST(:propertyId AS text), 0))) AS acquired
                """)
            .param("propertyId", propertyId)
            .query(Long.class)
            .single();
    }

    private void requireExpectedRevision(UUID propertyId, long expectedRevision) {
        var actual = currentRevision(propertyId);
        if (actual != expectedRevision) {
            throw new PricingPolicyException("STALE_INVENTORY_REVISION",
                "Inventory changed after the caller's observation");
        }
    }

    private long requireResultingRevision(UUID propertyId, long expectedRevision) {
        var result = currentRevision(propertyId);
        if (result != expectedRevision + 1) {
            throw commandFailed();
        }
        return result;
    }

    private long currentRevision(UUID propertyId) {
        return jdbc.sql("""
                SELECT revision FROM inventory_property_revisions
                WHERE property_id = :propertyId
                """)
            .param("propertyId", propertyId)
            .query(Long.class)
            .optional()
            .orElse(0L);
    }

    private StoredBlock lockBlock(UUID blockId, UUID propertyId) {
        return jdbc.sql("""
                SELECT id, property_id, block_type, status, start_date,
                       end_date_exclusive, version
                FROM inventory_blocks
                WHERE id = :id AND property_id = :propertyId
                FOR UPDATE
                """)
            .param("id", blockId)
            .param("propertyId", propertyId)
            .query((rs, rowNumber) -> new StoredBlock(
                rs.getObject("id", UUID.class),
                rs.getObject("property_id", UUID.class),
                rs.getString("block_type"), rs.getString("status"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date_exclusive", LocalDate.class),
                rs.getLong("version")))
            .optional()
            .orElseThrow(() -> new PricingPolicyException("INVENTORY_BLOCK_NOT_FOUND",
                "Inventory block was not found for the requested property"));
    }

    private void insertOutcome(
        UUID commandId,
        String idempotencyKey,
        String requestHash,
        String commandType,
        UUID blockId,
        UUID propertyId,
        String blockType,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        long expectedRevision,
        long resultingRevision,
        long blockVersion,
        AuthorizationEvidence authorization,
        String reason,
        String correlationId,
        OffsetDateTime occurredAt,
        OffsetDateTime releasedAt
    ) {
        jdbc.sql("""
                INSERT INTO inventory_block_commands(
                    id, idempotency_key, request_hash, command_type,
                    inventory_block_id, property_id, block_type, resulting_status,
                    start_date, end_date_exclusive, expected_inventory_revision,
                    resulting_inventory_revision, resulting_block_version,
                    actor_admin_account_id, actor_admin_profile_id,
                    authorization_version, permission_code, scope_type, partition_id,
                    property_branch_id, property_membership_version, reason,
                    correlation_id, occurred_at, released_at
                ) VALUES (
                    :id, :key, :requestHash, :commandType,
                    :blockId, :propertyId, :blockType, :status,
                    :startDate, :endDate, :expectedRevision,
                    :resultingRevision, :blockVersion,
                    :actorAccountId, :actorProfileId,
                    :authorizationVersion, :permissionCode, :scopeType, :partitionId,
                    :propertyBranchId, :propertyMembershipVersion, :reason,
                    :correlationId, :occurredAt, :releasedAt
                )
                """)
            .param("id", commandId)
            .param("key", idempotencyKey)
            .param("requestHash", requestHash)
            .param("commandType", commandType)
            .param("blockId", blockId)
            .param("propertyId", propertyId)
            .param("blockType", blockType)
            .param("status", status)
            .param("startDate", startDate)
            .param("endDate", endDate)
            .param("expectedRevision", expectedRevision)
            .param("resultingRevision", resultingRevision)
            .param("blockVersion", blockVersion)
            .param("actorAccountId", authorization.actorAccountId())
            .param("actorProfileId", authorization.actorProfileId())
            .param("authorizationVersion", authorization.authorizationVersion())
            .param("permissionCode", PERMISSION)
            .param("scopeType", authorization.scope().type())
            .param("partitionId", authorization.scope().partitionId())
            .param("propertyBranchId", authorization.scope().propertyBranchId())
            .param("propertyMembershipVersion",
                authorization.scope().propertyMembershipVersion())
            .param("reason", reason)
            .param("correlationId", correlationId)
            .param("occurredAt", occurredAt)
            .param("releasedAt", releasedAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .update();
    }

    private void writeAuditAndOutbox(
        UUID commandId,
        UUID blockId,
        UUID propertyId,
        String action,
        String blockType,
        String previousStatus,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        long blockVersion,
        long revision,
        AuthorizationEvidence authorization,
        String reason,
        String correlationId,
        OffsetDateTime occurredAt
    ) {
        var changes = new LinkedHashMap<String, Object>();
        changes.put("commandId", commandId.toString());
        changes.put("propertyId", propertyId.toString());
        changes.put("blockType", blockType);
        changes.put("previousStatus", previousStatus);
        changes.put("status", status);
        changes.put("startDate", startDate.toString());
        changes.put("endDateExclusive", endDate.toString());
        changes.put("blockVersion", blockVersion);
        changes.put("inventoryRevision", revision);
        changes.put("actorAdminProfileId", authorization.actorProfileId());
        changes.put("authorizationVersion", authorization.authorizationVersion());
        changes.put("permissionCode", PERMISSION);
        changes.put("scopeType", authorization.scope().type());
        changes.put("partitionId", authorization.scope().partitionId());
        changes.put("propertyBranchId", authorization.scope().propertyBranchId());
        changes.put("propertyMembershipVersion",
            authorization.scope().propertyMembershipVersion());
        changes.put("reason", reason);
        jdbc.sql("""
                INSERT INTO audit_events(
                    actor_account_type, actor_account_id, action, aggregate_type,
                    aggregate_id, changes, correlation_id, created_at
                ) VALUES (
                    'ADMIN', :actorId, :action, 'InventoryBlock', :blockId,
                    CAST(:changes AS jsonb), :correlationId, :occurredAt
                )
                """)
            .param("actorId", authorization.actorAccountId())
            .param("action", "INVENTORY_BLOCK_" + action)
            .param("blockId", blockId)
            .param("changes", writeJson(changes))
            .param("correlationId", correlationId)
            .param("occurredAt", occurredAt)
            .update();

        var event = new LinkedHashMap<String, Object>();
        event.put("commandId", commandId.toString());
        event.put("inventoryBlockId", blockId.toString());
        event.put("propertyId", propertyId.toString());
        event.put("action", action);
        event.put("blockType", blockType);
        event.put("status", status);
        event.put("startDate", startDate.toString());
        event.put("endDateExclusive", endDate.toString());
        event.put("blockVersion", blockVersion);
        event.put("inventoryRevision", revision);
        event.put("occurredAt", occurredAt.toString());
        jdbc.sql("""
                INSERT INTO outbox_events(
                    aggregate_type, aggregate_id, aggregate_version, event_type,
                    event_version, payload, correlation_id, causation_id, occurred_at
                ) VALUES (
                    'PropertyInventory', :propertyId, :revision, :eventType,
                    1, CAST(:payload AS jsonb), :correlationId, :causationId, :occurredAt
                )
                """)
            .param("propertyId", propertyId)
            .param("revision", revision)
            .param("eventType", EVENT_TYPE)
            .param("payload", writeJson(event))
            .param("correlationId", correlationId)
            .param("causationId", commandId.toString())
            .param("occurredAt", occurredAt)
            .update();
    }

    private void completeReservation(String key, UUID commandId) {
        int updated = jdbc.sql("""
                UPDATE command_idempotency
                SET resource_type = 'InventoryBlockCommand', resource_id = :resourceId,
                    response_code = 200,
                    response_body = CAST(:response AS jsonb),
                    status = 'SUCCEEDED', completed_at = now()
                WHERE idempotency_key = :key AND status = 'PROCESSING'
                """)
            .param("resourceId", commandId)
            .param("response", writeJson(Map.of("commandId", commandId.toString())))
            .param("key", key)
            .update();
        if (updated != 1) throw commandFailed();
    }

    private Optional<GenericCommand> genericCommand(String key) {
        return jdbc.sql("""
                SELECT command_type, actor_account_id, request_hash,
                       resource_id, status
                FROM command_idempotency WHERE idempotency_key = :key
                """)
            .param("key", key)
            .query((rs, rowNumber) -> new GenericCommand(
                rs.getString("command_type"),
                rs.getObject("actor_account_id", UUID.class),
                rs.getString("request_hash").trim(),
                rs.getObject("resource_id", UUID.class), rs.getString("status")))
            .optional();
    }

    private Optional<StoredCommand> commandForKey(String key) {
        return jdbc.sql(commandSelect() + " WHERE idempotency_key = :key")
            .param("key", key)
            .query(this::mapCommand)
            .optional();
    }

    private CommandResult requireCommand(UUID id) {
        return jdbc.sql(commandSelect() + " WHERE id = :id")
            .param("id", id)
            .query(this::mapCommand)
            .optional()
            .orElseThrow(() -> new PricingPolicyException(
                "IDEMPOTENCY_COMMAND_INCOMPLETE",
                "The idempotent inventory command has no durable result"))
            .toResult();
    }

    private String commandSelect() {
        return """
                SELECT id, idempotency_key, request_hash, command_type,
                       inventory_block_id, property_id, block_type, resulting_status,
                       start_date, end_date_exclusive, resulting_inventory_revision,
                       resulting_block_version, actor_admin_account_id,
                       property_branch_id, property_membership_version,
                       occurred_at, released_at
                FROM inventory_block_commands
                """;
    }

    private StoredCommand mapCommand(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredCommand(
            rs.getObject("id", UUID.class), rs.getString("idempotency_key"),
            rs.getString("request_hash").trim(), rs.getString("command_type"),
            rs.getObject("inventory_block_id", UUID.class),
            rs.getObject("property_id", UUID.class), rs.getString("block_type"),
            rs.getString("resulting_status"),
            rs.getObject("start_date", LocalDate.class),
            rs.getObject("end_date_exclusive", LocalDate.class),
            rs.getLong("resulting_block_version"),
            rs.getLong("resulting_inventory_revision"),
            rs.getObject("actor_admin_account_id", UUID.class),
            rs.getObject("property_branch_id", UUID.class),
            rs.getLong("property_membership_version"),
            rs.getObject("occurred_at", OffsetDateTime.class),
            rs.getObject("released_at", OffsetDateTime.class));
    }

    private void validateCreate(CreateDraft draft, AuthorizationEvidence authorization) {
        validateCommon(draft == null ? null : draft.idempotencyKey(),
            draft == null ? null : draft.propertyId(),
            draft == null ? -1 : draft.expectedInventoryRevision(),
            draft == null ? null : draft.reason(),
            draft == null ? null : draft.correlationId(), authorization);
        if (draft.startDate() == null || draft.endDateExclusive() == null
            || !draft.endDateExclusive().isAfter(draft.startDate())) {
            throw new PricingPolicyException("INVALID_INVENTORY_RANGE",
                "Inventory block end date must be after its start date");
        }
        if (!OPERATOR_TYPES.contains(draft.blockType())) {
            throw new PricingPolicyException("INVENTORY_BLOCK_TYPE_FORBIDDEN",
                "This inventory block type is controlled by its owning workflow");
        }
    }

    private void validateRelease(ReleaseDraft draft, AuthorizationEvidence authorization) {
        validateCommon(draft == null ? null : draft.idempotencyKey(),
            draft == null ? null : draft.propertyId(),
            draft == null ? -1 : draft.expectedInventoryRevision(),
            draft == null ? null : draft.reason(),
            draft == null ? null : draft.correlationId(), authorization);
        if (draft.inventoryBlockId() == null) {
            throw new PricingPolicyException("INVALID_INVENTORY_BLOCK_INPUT",
                "Inventory block ID is required");
        }
    }

    private void validateCommon(
        String key,
        UUID propertyId,
        long expectedRevision,
        String reason,
        String correlationId,
        AuthorizationEvidence authorization
    ) {
        if (key == null || !IDEMPOTENCY_KEY.matcher(key).matches()) {
            throw new PricingPolicyException("INVALID_IDEMPOTENCY_KEY",
                "Idempotency key has an invalid format");
        }
        if (propertyId == null || expectedRevision < 0
            || !trimmedLength(reason, 10, 1000) || !isCanonicalUuid(correlationId)) {
            throw new PricingPolicyException("INVALID_INVENTORY_BLOCK_INPUT",
                "Inventory command input is invalid");
        }
        if (authorization == null || authorization.actorAccountId() == null
            || authorization.actorProfileId() == null
            || authorization.authorizationVersion() < 1
            || !validScope(authorization.scope())) {
            throw new PricingPolicyException("INVENTORY_BLOCK_FORBIDDEN",
                "Inventory block command authorization is invalid");
        }
    }

    private boolean validScope(ScopeEvidence scope) {
        return scope != null && scope.propertyBranchId() != null
            && scope.propertyMembershipVersion() >= 0
            && (("ALL".equals(scope.type()) && scope.partitionId() == null)
                || ("BRANCH".equals(scope.type())
                    && scope.propertyBranchId().equals(scope.partitionId())));
    }

    private String createRequestHash(CreateDraft draft) {
        var canonical = new StringBuilder();
        appendCanonical(canonical, "INVENTORY_BLOCK_COMMAND_V1");
        appendCanonical(canonical, "CREATED");
        appendCanonical(canonical, draft.propertyId());
        appendCanonical(canonical, draft.blockType());
        appendCanonical(canonical, draft.startDate());
        appendCanonical(canonical, draft.endDateExclusive());
        appendCanonical(canonical, draft.expectedInventoryRevision());
        appendCanonical(canonical, draft.reason().trim());
        appendCanonical(canonical, draft.correlationId());
        return sha256(canonical.toString());
    }

    private String releaseRequestHash(ReleaseDraft draft) {
        var canonical = new StringBuilder();
        appendCanonical(canonical, "INVENTORY_BLOCK_COMMAND_V1");
        appendCanonical(canonical, "RELEASED");
        appendCanonical(canonical, draft.propertyId());
        appendCanonical(canonical, draft.inventoryBlockId());
        appendCanonical(canonical, draft.expectedInventoryRevision());
        appendCanonical(canonical, draft.reason().trim());
        appendCanonical(canonical, draft.correlationId());
        return sha256(canonical.toString());
    }

    private static void appendCanonical(StringBuilder target, Object value) {
        var text = String.valueOf(value);
        target.append(text.getBytes(StandardCharsets.UTF_8).length)
            .append(':').append(text).append(';');
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cannot serialize inventory evidence", failure);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
    }

    private static boolean trimmedLength(String value, int minimum, int maximum) {
        if (value == null || !value.equals(value.trim())) {
            return false;
        }
        int length = value.codePointCount(0, value.length());
        return length >= minimum && length <= maximum;
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || !value.equals(value.toLowerCase(java.util.Locale.ROOT))) {
            return false;
        }
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean hasSqlState(Throwable failure, String expected) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && expected.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static PricingPolicyException idempotencyReused() {
        return new PricingPolicyException("IDEMPOTENCY_KEY_REUSED",
            "Idempotency key was already used for a different inventory command");
    }

    private static PricingPolicyException commandFailed() {
        return new PricingPolicyException("INVENTORY_BLOCK_COMMAND_FAILED",
            "Inventory block command could not be completed");
    }

    public record CreateDraft(
        String idempotencyKey,
        UUID propertyId,
        String blockType,
        LocalDate startDate,
        LocalDate endDateExclusive,
        long expectedInventoryRevision,
        String reason,
        String correlationId
    ) {}

    public record ReleaseDraft(
        String idempotencyKey,
        UUID propertyId,
        UUID inventoryBlockId,
        long expectedInventoryRevision,
        String reason,
        String correlationId
    ) {}

    public record AuthorizationEvidence(
        UUID actorAccountId,
        UUID actorProfileId,
        long authorizationVersion,
        ScopeEvidence scope
    ) {}

    public record CommandResult(
        UUID commandId,
        String idempotencyKey,
        String requestHash,
        UUID inventoryBlockId,
        String action,
        UUID propertyId,
        String blockType,
        String status,
        LocalDate startDate,
        LocalDate endDateExclusive,
        long blockVersion,
        long inventoryRevision,
        UUID branchId,
        long propertyMembershipVersion,
        OffsetDateTime occurredAt,
        OffsetDateTime releasedAt
    ) {}

    private record StoredBlock(
        UUID id,
        UUID propertyId,
        String blockType,
        String status,
        LocalDate startDate,
        LocalDate endDateExclusive,
        long version
    ) {}

    private record GenericCommand(
        String commandType,
        UUID actorAccountId,
        String requestHash,
        UUID resourceId,
        String status
    ) {}

    private record StoredCommand(
        UUID id,
        String idempotencyKey,
        String requestHash,
        String commandType,
        UUID inventoryBlockId,
        UUID propertyId,
        String blockType,
        String status,
        LocalDate startDate,
        LocalDate endDateExclusive,
        long blockVersion,
        long inventoryRevision,
        UUID actorAdminAccountId,
        UUID branchId,
        long propertyMembershipVersion,
        OffsetDateTime occurredAt,
        OffsetDateTime releasedAt
    ) {
        CommandResult toResult() {
            var action = CREATE_COMMAND.equals(commandType) ? "CREATED" : "RELEASED";
            return new CommandResult(id, idempotencyKey, requestHash, inventoryBlockId,
                action, propertyId, blockType, status, startDate, endDateExclusive,
                blockVersion, inventoryRevision, branchId, propertyMembershipVersion,
                occurredAt, releasedAt);
        }
    }
}
