package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.booking.application.InventoryBlockCommandService.AuthorizationEvidence;
import com.vistay.booking.application.InventoryBlockCommandService.CommandResult;
import com.vistay.booking.application.InventoryBlockCommandService.CreateDraft;
import com.vistay.booking.application.InventoryBlockCommandService.ReleaseDraft;
import com.vistay.booking.application.InventoryScopePolicy.ScopeEvidence;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class InventoryBlockCommandIntegrationTest {
    private static final UUID ACTOR =
        UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROFILE =
        UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BRANCH =
        UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String CORRELATION =
        "77777777-7777-4777-8777-777777777777";
    private static final LocalDate START = LocalDate.of(2026, 11, 1);
    private static final Instant NOW = Instant.parse("2026-10-30T08:15:30Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("vistay_booking_inventory_command_test")
        .withUsername("vistay")
        .withPassword("vistay");

    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper json;
    private InventoryBlockCommandService commands;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        var dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + separator + "currentSchema=app,extensions,public",
            POSTGRES.getUsername(), POSTGRES.getPassword());
        var result = Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema("app")
            .schemas("app")
            .locations("classpath:db/migration")
            .load()
            .migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo("7");
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = JsonMapper.builder().build();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.sql("""
                TRUNCATE TABLE inventory_block_commands, command_idempotency,
                    audit_events, outbox_events, inventory_blocks,
                    inventory_property_revisions, bookings CASCADE
                """).update();
        commands = new InventoryBlockCommandService(jdbc, json,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsEveryOperatorTypeWithImmutableEvidenceAuditAndEvent() {
        for (var type : List.of("MAINTENANCE", "CLEANING", "OWNER_USE", "MANUAL")) {
            var propertyId = UUID.randomUUID();
            var draft = createDraft("inventory.create:" + type.toLowerCase(),
                propertyId, type, START, START.plusDays(2), 0);

            var result = create(draft, authorization(ACTOR));

            assertThat(result.action()).isEqualTo("CREATED");
            assertThat(result.propertyId()).isEqualTo(propertyId);
            assertThat(result.blockType()).isEqualTo(type);
            assertThat(result.status()).isEqualTo("ACTIVE");
            assertThat(result.blockVersion()).isZero();
            assertThat(result.inventoryRevision()).isEqualTo(1);
            assertThat(result.branchId()).isEqualTo(BRANCH);
            assertThat(result.propertyMembershipVersion()).isEqualTo(41);
            assertThat(result.occurredAt().toInstant()).isEqualTo(NOW);
            assertThat(result.releasedAt()).isNull();
            assertThat(result.requestHash()).isEqualTo(createHash(draft));
        }

        assertThat(count("inventory_blocks")).isEqualTo(4);
        assertThat(count("inventory_block_commands")).isEqualTo(4);
        assertThat(count("audit_events")).isEqualTo(4);
        assertThat(count("outbox_events")).isEqualTo(4);
        var payload = json.readTree(jdbc.sql("""
                SELECT payload::text FROM outbox_events ORDER BY occurred_at, id LIMIT 1
                """).query(String.class).single());
        assertThat(payload.get("commandId").asString()).isNotBlank();
        assertThat(payload.get("action").asString()).isEqualTo("CREATED");
        assertThat(payload.get("inventoryRevision").asLong()).isEqualTo(1);
        assertThat(payload.has("reason")).isFalse();
        assertThat(payload.has("actorAdminAccountId")).isFalse();
        assertThat(jdbc.sql("SELECT causation_id FROM outbox_events LIMIT 1")
            .query(String.class).single()).isNotBlank();
        var auditChanges = json.readTree(jdbc.sql("""
                SELECT changes::text FROM audit_events ORDER BY created_at, id LIMIT 1
                """).query(String.class).single());
        assertThat(auditChanges.get("actorAdminProfileId").asString())
            .isEqualTo(PROFILE.toString());
    }

    @Test
    void acceptsCanonicalCorrelationUuidWithoutVersionOrVariantRestriction() {
        var propertyId = UUID.randomUUID();
        var draft = createDraft("inventory.create:canonical-correlation", propertyId,
            "MANUAL", START, START.plusDays(1), 0);
        draft = new CreateDraft(draft.idempotencyKey(), draft.propertyId(), draft.blockType(),
            draft.startDate(), draft.endDateExclusive(), draft.expectedInventoryRevision(),
            draft.reason(), "00000000-0000-0000-0000-000000000000");

        create(draft, authorization(ACTOR));
        assertThat(jdbc.sql("""
                SELECT correlation_id FROM inventory_block_commands
                WHERE property_id = :propertyId
                """).param("propertyId", propertyId).query(String.class).single())
            .isEqualTo("00000000-0000-0000-0000-000000000000");
    }

    @Test
    void exactCreateAndReleaseReplayReturnsOriginalOutcomeWithoutNewFacts() {
        var propertyId = UUID.randomUUID();
        var createDraft = createDraft("inventory.create:replay", propertyId,
            "MANUAL", START, START.plusDays(1), 0);
        var created = create(createDraft, authorization(ACTOR));

        assertThat(create(createDraft, authorization(ACTOR))).isEqualTo(created);
        assertThat(revision(propertyId)).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);

        var releaseDraft = new ReleaseDraft("inventory.release:replay", propertyId,
            created.inventoryBlockId(), 1, "Release the completed manual block", CORRELATION);
        var released = release(releaseDraft, authorization(ACTOR));
        assertThat(released.action()).isEqualTo("RELEASED");
        assertThat(released.status()).isEqualTo("RELEASED");
        assertThat(released.blockVersion()).isEqualTo(1);
        assertThat(released.inventoryRevision()).isEqualTo(2);
        assertThat(released.releasedAt()).isEqualTo(released.occurredAt());

        assertThat(release(releaseDraft, authorization(ACTOR))).isEqualTo(released);
        assertThat(revision(propertyId)).isEqualTo(2);
        assertThat(count("inventory_block_commands")).isEqualTo(2);
        assertThat(count("audit_events")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);

        var secondRelease = new ReleaseDraft("inventory.release:second", propertyId,
            created.inventoryBlockId(), 2, "Attempt a second release command", CORRELATION);
        assertCode(() -> release(secondRelease, authorization(ACTOR)),
            "INVENTORY_BLOCK_ALREADY_RELEASED");
        assertThat(count("command_idempotency")).isEqualTo(2);
        assertThat(revision(propertyId)).isEqualTo(2);
    }

    @Test
    void releasesControlledTentativeOperatorBlock() {
        var propertyId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory_blocks(
                    id, property_id, block_type, status, start_date, end_date_exclusive
                ) VALUES (
                    :id, :propertyId, 'OWNER_USE', 'TENTATIVE', :startDate, :endDate
                )
                """)
            .param("id", blockId).param("propertyId", propertyId)
            .param("startDate", START).param("endDate", START.plusDays(1)).update();

        var released = release(new ReleaseDraft("inventory.release:tentative",
            propertyId, blockId, 1, "Release tentative owner-use block", CORRELATION),
            authorization(ACTOR));

        assertThat(released.action()).isEqualTo("RELEASED");
        assertThat(released.blockType()).isEqualTo("OWNER_USE");
        assertThat(released.blockVersion()).isEqualTo(1);
        assertThat(released.inventoryRevision()).isEqualTo(2);
        assertAllCommandTables(1);
    }

    @Test
    void keyReuseByActorActionOrPayloadIsRejected() {
        var propertyId = UUID.randomUUID();
        var draft = createDraft("inventory.create:identity", propertyId,
            "OWNER_USE", START, START.plusDays(2), 0);
        var created = create(draft, authorization(ACTOR));

        assertCode(() -> create(draft, authorization(UUID.randomUUID())),
            "IDEMPOTENCY_KEY_REUSED");
        var changed = new CreateDraft(draft.idempotencyKey(), propertyId, draft.blockType(),
            draft.startDate(), draft.endDateExclusive(), 0,
            "A different owner-use command reason", CORRELATION);
        assertCode(() -> create(changed, authorization(ACTOR)), "IDEMPOTENCY_KEY_REUSED");
        var release = new ReleaseDraft(draft.idempotencyKey(), propertyId,
            created.inventoryBlockId(), 1, "Release the owner-use inventory block", CORRELATION);
        assertCode(() -> release(release, authorization(ACTOR)), "IDEMPOTENCY_KEY_REUSED");
        assertThat(count("inventory_block_commands")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    @Test
    void revisionCasAdjacencyAndExclusionConflictFailWithoutPartialWrites() {
        var propertyId = UUID.randomUUID();
        assertCode(() -> create(createDraft("inventory.create:stale", propertyId,
            "MANUAL", START, START.plusDays(1), 1), authorization(ACTOR)),
            "STALE_INVENTORY_REVISION");
        assertAllCommandTables(0);

        create(createDraft("inventory.create:first", propertyId,
            "MAINTENANCE", START, START.plusDays(2), 0), authorization(ACTOR));
        create(createDraft("inventory.create:adjacent", propertyId,
            "CLEANING", START.plusDays(2), START.plusDays(3), 1), authorization(ACTOR));
        assertThat(revision(propertyId)).isEqualTo(2);

        assertCode(() -> create(createDraft("inventory.create:overlap", propertyId,
            "MANUAL", START.plusDays(1), START.plusDays(3), 2), authorization(ACTOR)),
            "INVENTORY_BLOCK_CONFLICT");
        assertThat(revision(propertyId)).isEqualTo(2);
        assertThat(count("inventory_blocks")).isEqualTo(2);
        assertThat(count("inventory_block_commands")).isEqualTo(2);
        assertThat(count("command_idempotency")).isEqualTo(2);
        assertThat(count("audit_events")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);
    }

    @Test
    void invalidKeyRangeTypeReasonAndCorrelationNeverReserveACommand() {
        var propertyId = UUID.randomUUID();
        var valid = createDraft("inventory.create:validation", propertyId,
            "MANUAL", START, START.plusDays(1), 0);
        assertCode(() -> create(new CreateDraft("bad key", propertyId, "MANUAL",
            START, START.plusDays(1), 0, valid.reason(), CORRELATION),
            authorization(ACTOR)), "INVALID_IDEMPOTENCY_KEY");
        assertCode(() -> create(new CreateDraft("inventory.create:range", propertyId,
            "MANUAL", START, START, 0, valid.reason(), CORRELATION),
            authorization(ACTOR)), "INVALID_INVENTORY_RANGE");
        assertCode(() -> create(new CreateDraft("inventory.create:type", propertyId,
            "BOOKING", START, START.plusDays(1), 0, valid.reason(), CORRELATION),
            authorization(ACTOR)), "INVENTORY_BLOCK_TYPE_FORBIDDEN");
        assertCode(() -> create(new CreateDraft("inventory.create:reason", propertyId,
            "MANUAL", START, START.plusDays(1), 0, "short", CORRELATION),
            authorization(ACTOR)), "INVALID_INVENTORY_BLOCK_INPUT");
        assertCode(() -> create(new CreateDraft("inventory.create:correlation", propertyId,
            "MANUAL", START, START.plusDays(1), 0, valid.reason(), "not-a-uuid"),
            authorization(ACTOR)), "INVALID_INVENTORY_BLOCK_INPUT");
        assertThat(count("inventory_blocks")).isZero();
        assertThat(count("inventory_property_revisions")).isZero();
        assertAllCommandTables(0);
    }

    @Test
    void releaseRejectsForeignPropertyChannelAndCancelledRowsWithoutWrites() {
        var propertyId = UUID.randomUUID();
        var channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory_blocks(
                    id, property_id, block_type, status, start_date, end_date_exclusive,
                    channel_provider, channel_external_block_id
                ) VALUES (
                    :id, :propertyId, 'CHANNEL', 'ACTIVE', :startDate, :endDate,
                    'CHANNEX', :externalId
                )
                """)
            .param("id", channelId).param("propertyId", propertyId)
            .param("startDate", START).param("endDate", START.plusDays(1))
            .param("externalId", "external-" + channelId).update();
        assertThat(revision(propertyId)).isEqualTo(1);

        assertCode(() -> release(new ReleaseDraft("inventory.release:channel", propertyId,
            channelId, 1, "Attempt to release a channel block", CORRELATION),
            authorization(ACTOR)), "INVENTORY_BLOCK_TYPE_FORBIDDEN");
        var assertedForeignProperty = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory_blocks(
                    property_id, block_type, status, start_date, end_date_exclusive
                ) VALUES (:propertyId, 'MANUAL', 'ACTIVE', :startDate, :endDate)
                """).param("propertyId", assertedForeignProperty)
            .param("startDate", START).param("endDate", START.plusDays(1)).update();
        assertCode(() -> release(new ReleaseDraft("inventory.release:foreign",
            assertedForeignProperty, channelId, 0, "Attempt release from another property",
            CORRELATION), authorization(ACTOR)), "INVENTORY_BLOCK_NOT_FOUND");
        assertThat(count("inventory_block_commands")).isZero();
        assertThat(count("command_idempotency")).isZero();
        assertThat(count("audit_events")).isZero();
        assertThat(count("outbox_events")).isZero();
        assertThat(revision(propertyId)).isEqualTo(1);

        var cancelledProperty = UUID.randomUUID();
        var cancelledId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory_blocks(
                    id, property_id, block_type, status, start_date,
                    end_date_exclusive, released_at
                ) VALUES (
                    :id, :propertyId, 'MANUAL', 'CANCELLED', :startDate,
                    :endDate, now()
                )
                """)
            .param("id", cancelledId).param("propertyId", cancelledProperty)
            .param("startDate", START).param("endDate", START.plusDays(1)).update();
        assertCode(() -> release(new ReleaseDraft("inventory.release:cancelled",
            cancelledProperty, cancelledId, 1, "Attempt release of cancelled block",
            CORRELATION), authorization(ACTOR)), "INVENTORY_BLOCK_NOT_RELEASABLE");
    }

    @Test
    void concurrentExactKeyReplaysOneCommitAndSharedRevisionAllowsAtMostOneWinner()
        throws Exception {
        var propertyId = UUID.randomUUID();
        var draft = createDraft("inventory.create:concurrent-replay", propertyId,
            "MANUAL", START, START.plusDays(1), 0);
        var replayOutcomes = concurrentCreate(draft, draft);
        assertThat(replayOutcomes).allSatisfy(outcome -> {
            assertThat(outcome.errorCode()).isNull();
            assertThat(outcome.result()).isNotNull();
        });
        assertThat(replayOutcomes.get(0).result()).isEqualTo(replayOutcomes.get(1).result());
        assertAllCommandTables(1);

        reset();
        propertyId = UUID.randomUUID();
        var first = createDraft("inventory.create:race-a", propertyId,
            "MANUAL", START, START.plusDays(1), 0);
        var second = createDraft("inventory.create:race-b", propertyId,
            "CLEANING", START.plusDays(1), START.plusDays(2), 0);
        var race = concurrentCreate(first, second);
        assertThat(race).filteredOn(outcome -> outcome.result() != null).hasSize(1);
        assertThat(race).filteredOn(outcome -> "STALE_INVENTORY_REVISION".equals(
            outcome.errorCode())).hasSize(1);
        assertAllCommandTables(1);
    }

    @Test
    void injectedOutcomeFailureRollsBackBlockRevisionReservationAuditAndOutbox() {
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_inventory_outcome_test()
                RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'injected inventory outcome failure';
                END;
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER trg_fail_inventory_outcome_test
                BEFORE INSERT ON inventory_block_commands
                FOR EACH ROW EXECUTE FUNCTION fail_inventory_outcome_test()
                """).update();
        try {
            var propertyId = UUID.randomUUID();
            assertCode(() -> create(createDraft("inventory.create:rollback", propertyId,
                "MANUAL", START, START.plusDays(1), 0), authorization(ACTOR)),
                "INVENTORY_BLOCK_COMMAND_FAILED");
            assertThat(count("inventory_blocks")).isZero();
            assertThat(count("inventory_property_revisions")).isZero();
            assertAllCommandTables(0);
        } finally {
            jdbc.sql("DROP TRIGGER trg_fail_inventory_outcome_test ON inventory_block_commands")
                .update();
            jdbc.sql("DROP FUNCTION fail_inventory_outcome_test()")
                .update();
        }
    }

    @Test
    void commandOutcomesAndHardenedLegacyEvidenceCannotBeMutatedOrCascaded() {
        var propertyId = UUID.randomUUID();
        var result = create(createDraft("inventory.create:immutable", propertyId,
            "MANUAL", START, START.plusDays(1), 0), authorization(ACTOR));
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE inventory_block_commands SET reason = 'Mutated durable reason'
                WHERE id = :id
                """).param("id", result.commandId()).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("immutable");

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE inventory_blocks SET released_at = now()
                WHERE id = :id
                """).param("id", result.inventoryBlockId()).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_inventory_release");
    }

    private List<ConcurrentOutcome> concurrentCreate(CreateDraft first, CreateDraft second)
        throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstFuture = executor.submit(() -> concurrentCreate(ready, start, first));
            var secondFuture = executor.submit(() -> concurrentCreate(ready, start, second));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(firstFuture.get(15, TimeUnit.SECONDS),
                secondFuture.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private ConcurrentOutcome concurrentCreate(
        CountDownLatch ready,
        CountDownLatch start,
        CreateDraft draft
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            return new ConcurrentOutcome(null, "START_TIMEOUT");
        }
        try {
            return new ConcurrentOutcome(create(draft, authorization(ACTOR)), null);
        } catch (PricingPolicyException expected) {
            return new ConcurrentOutcome(null, expected.code());
        }
    }

    private CreateDraft createDraft(
        String key,
        UUID propertyId,
        String type,
        LocalDate start,
        LocalDate end,
        long expectedRevision
    ) {
        return new CreateDraft(key, propertyId, type, start, end, expectedRevision,
            "Authorized operator inventory reason", CORRELATION);
    }

    private AuthorizationEvidence authorization(UUID actor) {
        return new AuthorizationEvidence(actor, PROFILE, 29,
            new ScopeEvidence("ALL", null, BRANCH, 41));
    }

    private CommandResult create(CreateDraft draft, AuthorizationEvidence authorization) {
        return Objects.requireNonNull(transactions.execute(
            status -> commands.create(draft, authorization)));
    }

    private CommandResult release(ReleaseDraft draft, AuthorizationEvidence authorization) {
        return Objects.requireNonNull(transactions.execute(
            status -> commands.release(draft, authorization)));
    }

    private void assertCode(
        org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
        String expected
    ) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PricingPolicyException.class,
            error -> assertThat(error.code()).isEqualTo(expected));
    }

    private long revision(UUID propertyId) {
        return jdbc.sql("""
                SELECT revision FROM inventory_property_revisions
                WHERE property_id = :propertyId
                """).param("propertyId", propertyId).query(Long.class).single();
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private void assertAllCommandTables(long expected) {
        assertThat(count("inventory_block_commands")).isEqualTo(expected);
        assertThat(count("command_idempotency")).isEqualTo(expected);
        assertThat(count("audit_events")).isEqualTo(expected);
        assertThat(count("outbox_events")).isEqualTo(expected);
    }

    private String createHash(CreateDraft draft) {
        try {
            var canonical = new StringBuilder();
            for (var value : List.of("INVENTORY_BLOCK_COMMAND_V1", "CREATED",
                draft.propertyId().toString(), draft.blockType(), draft.startDate().toString(),
                draft.endDateExclusive().toString(),
                Long.toString(draft.expectedInventoryRevision()), draft.reason().trim(),
                draft.correlationId())) {
                canonical.append(value.getBytes(StandardCharsets.UTF_8).length)
                    .append(':').append(value).append(';');
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record ConcurrentOutcome(CommandResult result, String errorCode) {}
}
