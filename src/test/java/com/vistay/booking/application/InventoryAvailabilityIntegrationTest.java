package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.booking.application.InventoryScopePolicy.ScopeEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.sql.Types;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class InventoryAvailabilityIntegrationTest {
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);
    private static final UUID BRANCH_ID =
        UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Instant OBSERVED_AT = Instant.parse("2026-10-30T06:00:00Z");

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("vistay_booking_inventory_test")
        .withUsername("vistay")
        .withPassword("vistay");

    private static JdbcClient jdbc;
    private static InventoryAvailabilityService availability;

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
        availability = new InventoryAvailabilityService(jdbc,
            Clock.fixed(OBSERVED_AT, ZoneOffset.UTC),
            new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.sql("""
            TRUNCATE TABLE inventory_blocks, inventory_property_revisions, bookings CASCADE
            """).update();
    }

    @Test
    void availableUnitHasAuthoritativeEmptyObservation() {
        var propertyId = UUID.randomUUID();

        var result = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));

        assertThat(result.propertyId()).isEqualTo(propertyId);
        assertThat(result.checkInDate()).isEqualTo(NOVEMBER);
        assertThat(result.checkOutDate()).isEqualTo(NOVEMBER.plusDays(2));
        assertThat(result.available()).isTrue();
        assertThat(result.inventoryRevision()).isZero();
        assertThat(result.branchId()).isEqualTo(BRANCH_ID);
        assertThat(result.propertyMembershipVersion()).isEqualTo(41);
        assertThat(result.observedAt()).isEqualTo(OBSERVED_AT);
        assertThat(result.overlaps()).isEmpty();
    }

    @Test
    void repeatedAndConcurrentReadersCreateNoHoldAndDoNotAdvanceRevision() throws Exception {
        var propertyId = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> observeAfter(ready, start, propertyId));
            var second = executor.submit(() -> observeAfter(ready, start, propertyId));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(10, TimeUnit.SECONDS).available()).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS).available()).isTrue();
            var repeated = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));
            assertThat(repeated.inventoryRevision()).isZero();
            assertThat(repeated.overlaps()).isEmpty();
            assertThat(jdbc.sql("SELECT count(*) FROM inventory_blocks")
                .query(Long.class).single()).isZero();
            assertThat(jdbc.sql("SELECT count(*) FROM inventory_property_revisions")
                .query(Long.class).single()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void everyOperationalAndChannelTypeBlocksWhenTentativeOrActive() {
        for (var status : List.of("TENTATIVE", "ACTIVE")) {
            for (var type : List.of("BOOKING", "MAINTENANCE", "CLEANING",
                "OWNER_USE", "MANUAL", "CHANNEL")) {
                var propertyId = UUID.randomUUID();
                insertBlock(propertyId, type, status, NOVEMBER, NOVEMBER.plusDays(2),
                    type.equals("CHANNEL") ? "CHANNEX" : null,
                    type.equals("CHANNEL") ? "channel-" + UUID.randomUUID() : null);

                var result = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));

                assertThat(result.available()).as(type + "/" + status).isFalse();
                assertThat(result.inventoryRevision()).as(type + "/" + status).isEqualTo(1);
                assertThat(result.overlaps()).singleElement().satisfies(overlap -> {
                    assertThat(overlap.type()).isEqualTo(type);
                    assertThat(overlap.status()).isEqualTo(status);
                    assertThat(overlap.startDate()).isEqualTo(NOVEMBER);
                    assertThat(overlap.endDateExclusive()).isEqualTo(NOVEMBER.plusDays(2));
                    assertThat(overlap.channelProvider())
                        .isEqualTo(type.equals("CHANNEL") ? "CHANNEX" : null);
                });
            }
        }
    }

    @Test
    void tentativeBlocksButReleasedAndCancelledAreSanitizedNonBlockingHistory() {
        var propertyId = UUID.randomUUID();
        insertBlock(propertyId, "MAINTENANCE", "RELEASED", NOVEMBER,
            NOVEMBER.plusDays(2), null, null);
        insertBlock(propertyId, "MANUAL", "CANCELLED", NOVEMBER,
            NOVEMBER.plusDays(2), null, null);

        var historical = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));
        assertThat(historical.available()).isTrue();
        assertThat(historical.inventoryRevision()).isEqualTo(2);
        assertThat(historical.overlaps()).extracting(overlap -> overlap.status())
            .containsExactlyInAnyOrder("RELEASED", "CANCELLED");

        var tentativeId = insertBlock(propertyId, "OWNER_USE", "TENTATIVE",
            NOVEMBER, NOVEMBER.plusDays(2), null, null);
        var blocked = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));
        assertThat(blocked.available()).isFalse();
        assertThat(blocked.inventoryRevision()).isEqualTo(3);
        assertThat(blocked.overlaps()).extracting(overlap -> overlap.status())
            .containsExactlyInAnyOrder("RELEASED", "CANCELLED", "TENTATIVE");

        jdbc.sql("DELETE FROM inventory_blocks WHERE id = :id")
            .param("id", tentativeId).update();
        var releasedAgain = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));
        assertThat(releasedAgain.available()).isTrue();
        assertThat(releasedAgain.inventoryRevision()).isEqualTo(4);
        assertThat(releasedAgain.overlaps()).extracting(overlap -> overlap.status())
            .containsExactlyInAnyOrder("RELEASED", "CANCELLED");
    }

    @Test
    void overlappingHistoryIsDeterministicallyOrderedAndChannelIdentityIsSanitized() {
        var propertyId = UUID.randomUUID();
        insertBlock(propertyId, "MANUAL", "RELEASED", NOVEMBER,
            NOVEMBER.plusDays(3), null, null);
        insertBlock(propertyId, "CHANNEL", "RELEASED", NOVEMBER,
            NOVEMBER.plusDays(2), "ZETA", "secret-zeta-reference");
        insertBlock(propertyId, "CHANNEL", "CANCELLED", NOVEMBER,
            NOVEMBER.plusDays(2), "BETA", "secret-beta-reference");
        insertBlock(propertyId, "CHANNEL", "CANCELLED", NOVEMBER,
            NOVEMBER.plusDays(2), "ALPHA", "secret-alpha-reference");

        var result = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));

        assertThat(result.available()).isTrue();
        assertThat(result.overlaps()).extracting(
            overlap -> overlap.type() + "/" + overlap.status() + "/"
                + overlap.channelProvider())
            .containsExactly(
                "CHANNEL/CANCELLED/ALPHA",
                "CHANNEL/CANCELLED/BETA",
                "CHANNEL/RELEASED/ZETA",
                "MANUAL/RELEASED/null");
        assertThat(InventoryAvailabilityService.OverlapEvidence.class.getRecordComponents())
            .extracting(java.lang.reflect.RecordComponent::getName)
            .containsExactly("type", "status", "startDate", "endDateExclusive",
                "channelProvider");
    }

    @Test
    void halfOpenAdjacencyDoesNotOverlapAndExclusionRemainsConcurrencyGuard() {
        var propertyId = UUID.randomUUID();
        insertBlock(propertyId, "CLEANING", "ACTIVE", NOVEMBER.plusDays(2),
            NOVEMBER.plusDays(4), null, null);

        assertThat(observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2)).available()).isTrue();
        assertThat(observe(propertyId, NOVEMBER.plusDays(4),
            NOVEMBER.plusDays(6)).available()).isTrue();
        assertThat(observe(propertyId, NOVEMBER.plusDays(3),
            NOVEMBER.plusDays(5)).available()).isFalse();

        assertThatThrownBy(() -> insertBlock(propertyId, "MAINTENANCE", "TENTATIVE",
                NOVEMBER.plusDays(3), NOVEMBER.plusDays(5), null, null))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ex_inventory_block_overlap");

        insertBlock(propertyId, "MAINTENANCE", "ACTIVE", NOVEMBER,
            NOVEMBER.plusDays(2), null, null);
        insertBlock(propertyId, "MAINTENANCE", "ACTIVE", NOVEMBER.plusDays(4),
            NOVEMBER.plusDays(5), null, null);
    }

    @Test
    void invalidOrZeroLengthStayIsRejectedBeforeDatabaseRead() {
        var propertyId = UUID.randomUUID();

        assertInvalidRange(propertyId, NOVEMBER, NOVEMBER);
        assertInvalidRange(propertyId, NOVEMBER.plusDays(1), NOVEMBER);
        assertInvalidRange(propertyId, null, NOVEMBER);
    }

    @Test
    void channelIdentityIsRequiredTrimmedAndIdempotentlyUniqueAcrossPropertiesAndStatuses() {
        var firstProperty = UUID.randomUUID();
        insertBlock(firstProperty, "CHANNEL", "RELEASED", NOVEMBER,
            NOVEMBER.plusDays(1), "Channex", "external-block-1");

        assertThatThrownBy(() -> insertBlock(UUID.randomUUID(), "CHANNEL", "ACTIVE",
                NOVEMBER.plusDays(2), NOVEMBER.plusDays(3), "channex", "external-block-1"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ux_inventory_channel_identity");
        assertThatThrownBy(() -> insertBlock(UUID.randomUUID(), "CHANNEL", "ACTIVE",
                NOVEMBER, NOVEMBER.plusDays(1), null, "external-block-2"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_inventory_channel_identity");
        assertThatThrownBy(() -> insertBlock(UUID.randomUUID(), "CHANNEL", "ACTIVE",
                NOVEMBER, NOVEMBER.plusDays(1), " Channex", "external-block-3"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_inventory_channel_identity");
        assertThatThrownBy(() -> insertBlock(UUID.randomUUID(), "MAINTENANCE", "ACTIVE",
                NOVEMBER, NOVEMBER.plusDays(1), "Channex", "not-a-channel"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ck_inventory_channel_identity");

        insertBlock(UUID.randomUUID(), "CHANNEL", "ACTIVE", NOVEMBER,
            NOVEMBER.plusDays(1), "OTHER_PROVIDER", "external-block-1");
    }

    @Test
    void revisionAdvancesForInsertEveryUpdateDeleteAndBothSidesOfPropertyMove() {
        var oldProperty = UUID.randomUUID();
        var newProperty = UUID.randomUUID();
        var blockId = insertBlock(oldProperty, "MANUAL", "ACTIVE", NOVEMBER,
            NOVEMBER.plusDays(1), null, null);
        assertThat(revision(oldProperty)).isEqualTo(1);

        jdbc.sql("""
                UPDATE inventory_blocks
                SET status = 'RELEASED', released_at = now(), version = version + 1
                WHERE id = :id
                """).param("id", blockId).update();
        assertThat(revision(oldProperty)).isEqualTo(2);

        jdbc.sql("UPDATE inventory_blocks SET property_id = :propertyId WHERE id = :id")
            .param("propertyId", newProperty).param("id", blockId).update();
        assertThat(revision(oldProperty)).isEqualTo(3);
        assertThat(revision(newProperty)).isEqualTo(1);

        jdbc.sql("DELETE FROM inventory_blocks WHERE id = :id").param("id", blockId).update();
        assertThat(revision(newProperty)).isEqualTo(2);
        assertThat(observe(newProperty, NOVEMBER, NOVEMBER.plusDays(1)).inventoryRevision())
            .isEqualTo(2);
    }

    @Test
    void concurrentMutationsNeverProduceRevisionConflictTornRead() throws Exception {
        var propertyId = UUID.randomUUID();
        var blockId = UUID.randomUUID();
        var started = new CountDownLatch(1);
        var finished = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var writer = executor.submit(() -> {
                started.countDown();
                try {
                    for (int index = 0; index < 30; index++) {
                        insertBlock(blockId, propertyId, "MAINTENANCE", "ACTIVE",
                            NOVEMBER, NOVEMBER.plusDays(1), null, null);
                        jdbc.sql("DELETE FROM inventory_blocks WHERE id = :id")
                            .param("id", blockId).update();
                    }
                } catch (Throwable unexpected) {
                    failure.set(unexpected);
                } finally {
                    finished.set(true);
                }
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            var observations = 0;
            while (!finished.get() || observations < 60) {
                var result = observe(propertyId, NOVEMBER, NOVEMBER.plusDays(1));
                if (result.inventoryRevision() % 2 == 0) {
                    assertThat(result.available()).isTrue();
                    assertThat(result.overlaps()).isEmpty();
                } else {
                    assertThat(result.available()).isFalse();
                    assertThat(result.overlaps()).singleElement()
                        .satisfies(overlap -> assertThat(overlap.status()).isEqualTo("ACTIVE"));
                }
                observations++;
            }
            writer.get(20, TimeUnit.SECONDS);
            assertThat(failure.get()).isNull();
            assertThat(revision(propertyId)).isEqualTo(60);
        } finally {
            executor.shutdownNow();
        }
    }

    private InventoryAvailabilityService.Availability observe(
        UUID propertyId,
        LocalDate checkIn,
        LocalDate checkOut
    ) {
        return availability.observe(propertyId, checkIn, checkOut,
            new ScopeEvidence("ALL", null, BRANCH_ID, 41));
    }

    private InventoryAvailabilityService.Availability observeAfter(
        CountDownLatch ready,
        CountDownLatch start,
        UUID propertyId
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Concurrent read start was not released");
        }
        return observe(propertyId, NOVEMBER, NOVEMBER.plusDays(2));
    }

    private void assertInvalidRange(UUID propertyId, LocalDate checkIn, LocalDate checkOut) {
        assertThatThrownBy(() -> observe(propertyId, checkIn, checkOut))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_INVENTORY_RANGE"));
    }

    private long revision(UUID propertyId) {
        return jdbc.sql("""
                SELECT revision FROM inventory_property_revisions WHERE property_id = :propertyId
                """).param("propertyId", propertyId).query(Long.class).single();
    }

    private UUID insertBlock(
        UUID propertyId,
        String type,
        String status,
        LocalDate start,
        LocalDate end,
        String provider,
        String externalReference
    ) {
        var id = UUID.randomUUID();
        insertBlock(id, propertyId, type, status, start, end, provider, externalReference);
        return id;
    }

    private void insertBlock(
        UUID id,
        UUID propertyId,
        String type,
        String status,
        LocalDate start,
        LocalDate end,
        String provider,
        String externalReference
    ) {
        UUID bookingId = null;
        if ("BOOKING".equals(type)) {
            bookingId = createBooking(propertyId, start, end);
        }
        var columns = new StringBuilder(
            "id, property_id, block_type, status, start_date, end_date_exclusive");
        var values = new StringBuilder(
            ":id, :propertyId, :type, :status, :startDate, :endDate");
        if (bookingId != null) {
            columns.append(", booking_id");
            values.append(", :bookingId");
        }
        if (provider != null || externalReference != null) {
            columns.append(", channel_provider, channel_external_block_id");
            values.append(", :provider, :externalReference");
        }
        if ("RELEASED".equals(status) || "CANCELLED".equals(status)) {
            columns.append(", released_at");
            values.append(", now()");
        }
        var statement = jdbc.sql("INSERT INTO inventory_blocks (" + columns
                + ") VALUES (" + values + ")")
            .param("id", id)
            .param("propertyId", propertyId)
            .param("type", type)
            .param("status", status)
            .param("startDate", start)
            .param("endDate", end);
        if (bookingId != null) {
            statement = statement.param("bookingId", bookingId);
        }
        if (provider != null || externalReference != null) {
            statement = statement.param("provider", provider, Types.VARCHAR)
                .param("externalReference", externalReference, Types.VARCHAR);
        }
        statement.update();
    }

    private UUID createBooking(UUID propertyId, LocalDate start, LocalDate end) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO bookings(
                    id, booking_code, property_id, source_channel, stay_type, status,
                    check_in_date, check_out_date, adults, children,
                    guest_name, guest_phone, guest_snapshot, property_snapshot,
                    contract_term_snapshot, price_unit, duration_value,
                    unit_price_snapshot, subtotal_amount, discount_amount,
                    fee_amount, tax_amount, total_amount, currency,
                    price_breakdown, policy_snapshot, created_by_account_type,
                    created_by_account_id
                ) VALUES (
                    :id, :code, :propertyId, 'ADMIN_PORTAL', 'SHORT_TERM', 'CONFIRMED',
                    :startDate, :endDate, 1, 0,
                    'Sanitized integration guest', '0000000000', '{}'::jsonb, '{}'::jsonb,
                    '{}'::jsonb, 'NIGHT', 1,
                    100, 100, 0,
                    0, 0, 100, 'VND',
                    '{}'::jsonb, '{}'::jsonb, 'ADMIN', :actorId
                )
                """)
            .param("id", id)
            .param("code", "INV-" + id)
            .param("propertyId", propertyId)
            .param("startDate", start)
            .param("endDate", end)
            .param("actorId", UUID.randomUUID())
            .update();
        return id;
    }
}
