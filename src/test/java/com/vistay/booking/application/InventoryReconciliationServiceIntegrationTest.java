package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class InventoryReconciliationServiceIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withDatabaseName("vistay_booking_inventory_reconciliation_test")
            .withUsername("vistay")
            .withPassword("vistay");
    private static final Instant NOW = Instant.parse("2026-10-30T04:00:00Z");

    private static JdbcClient jdbc;
    private static InventoryReconciliationService service;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        var dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + separator + "currentSchema=app,extensions,public",
            POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).defaultSchema("app").schemas("app")
            .locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        service = new InventoryReconciliationService(jdbc,
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterAll static void stop() { POSTGRES.stop(); }

    @BeforeEach
    void reset() {
        jdbc.sql("""
                TRUNCATE TABLE inventory_block_commands, command_idempotency,
                    audit_events, outbox_events, inventory_blocks,
                    inventory_property_revisions, bookings CASCADE
                """).update();
    }

    @Test
    void returnsEmptyRevisionZeroAndExactHeadNotModified() {
        var propertyId = UUID.randomUUID();
        var empty = service.reconcile(propertyId, 0);
        assertThat(empty.inventoryRevision()).isZero();
        assertThat(empty.notModified()).isTrue();
        assertThat(empty.blocks()).isEmpty();
        assertThat(empty.observedAt().toInstant()).isEqualTo(NOW);

        insert(propertyId, "MANUAL", "ACTIVE", LocalDate.of(2027, 1, 5),
            LocalDate.of(2027, 1, 7), null);
        var unchanged = service.reconcile(propertyId, 1);
        assertThat(unchanged.notModified()).isTrue();
        assertThat(unchanged.blocks()).isEmpty();
    }

    @Test
    void returnsCompleteOrderedCurrentProjectionWithoutPrivateEvidence() {
        var propertyId = UUID.randomUUID();
        insert(propertyId, "MANUAL", "RELEASED", LocalDate.of(2027, 1, 1),
            LocalDate.of(2027, 1, 2), null);
        var later = insert(propertyId, "CHANNEL", "ACTIVE", LocalDate.of(2027, 1, 5),
            LocalDate.of(2027, 1, 7), "CHANNEX");
        var first = insert(propertyId, "CLEANING", "TENTATIVE",
            LocalDate.of(2027, 1, 2), LocalDate.of(2027, 1, 3), null);

        var snapshot = service.reconcile(propertyId, 0);

        assertThat(snapshot.inventoryRevision()).isEqualTo(3);
        assertThat(snapshot.notModified()).isFalse();
        assertThat(snapshot.blocks()).extracting(InventoryReconciliationService.Block::id)
            .containsExactly(first, later);
        assertThat(snapshot.blocks().get(0).channelProvider()).isNull();
        assertThat(snapshot.blocks().get(1).channelProvider()).isEqualTo("CHANNEX");
    }

    @Test
    void rejectsConsumerRevisionAheadOfBookingHead() {
        var propertyId = UUID.randomUUID();
        assertThatThrownBy(() -> service.reconcile(propertyId, 1))
            .isInstanceOf(PricingPolicyException.class)
            .extracting(failure -> ((PricingPolicyException) failure).code())
            .isEqualTo("INVENTORY_RECONCILIATION_AHEAD");
    }

    private UUID insert(
        UUID propertyId,
        String type,
        String status,
        LocalDate start,
        LocalDate end,
        String provider
    ) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory_blocks(
                    id, property_id, block_type, status, start_date,
                    end_date_exclusive, reason, channel_provider,
                    channel_external_block_id, released_at
                ) VALUES (
                    :id, :propertyId, :type, :status, :startDate,
                    :endDate, 'Private repair reason', :provider,
                    :externalId, :releasedAt
                )
                """)
            .param("id", id).param("propertyId", propertyId).param("type", type)
            .param("status", status).param("startDate", start).param("endDate", end)
            .param("provider", provider, java.sql.Types.VARCHAR)
            .param("externalId", provider == null ? null : "private-external-ref",
                java.sql.Types.VARCHAR)
            .param("releasedAt", java.util.Set.of("RELEASED", "CANCELLED").contains(status)
                ? java.time.OffsetDateTime.now(ZoneOffset.UTC) : null,
                java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .update();
        return id;
    }
}
