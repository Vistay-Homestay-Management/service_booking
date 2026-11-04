package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class InventoryBlockManagementServiceIntegrationTest {
    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withDatabaseName("vistay_booking_inventory_management_test")
            .withUsername("vistay")
            .withPassword("vistay");
    private static final UUID PROPERTY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");

    private static JdbcClient jdbc;
    private static InventoryBlockManagementService service;

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
        service = new InventoryBlockManagementService(jdbc);
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
    }

    @Test
    void keysetPagesCurrentAndHistoricalBlocksWithoutPrivateReferences() {
        var first = insert("MAINTENANCE", "ACTIVE", "2027-01-01", "2027-01-02",
            "2026-11-03T03:00:00Z", null);
        var second = insert("CLEANING", "RELEASED", "2027-01-02", "2027-01-03",
            "2026-11-03T02:00:00Z", null);
        var third = insert("CHANNEL", "ACTIVE", "2027-01-03", "2027-01-04",
            "2026-11-03T01:00:00Z", "CHANNEX");

        var pageOne = service.list(new InventoryBlockManagementService.Query(
            PROPERTY_ID, null, null, List.of(), List.of(), 2, null));
        assertThat(pageOne.nodes()).extracting(
            InventoryBlockManagementService.Block::inventoryBlockId)
            .containsExactly(first.toString(), second.toString());
        assertThat(pageOne.pageInfo().hasNextPage()).isTrue();
        assertThat(pageOne.pageInfo().endCursor()).isNotBlank();
        assertThat(pageOne.inventoryRevision()).isEqualTo(3);

        var pageTwo = service.list(new InventoryBlockManagementService.Query(
            PROPERTY_ID, null, null, List.of(), List.of(), 2,
            pageOne.pageInfo().endCursor()));
        assertThat(pageTwo.nodes()).extracting(
            InventoryBlockManagementService.Block::inventoryBlockId)
            .containsExactly(third.toString());
        assertThat(pageTwo.pageInfo().hasNextPage()).isFalse();
        assertThat(pageTwo.nodes().get(0).channelProvider()).isEqualTo("CHANNEX");
    }

    @Test
    void appliesHalfOpenRangeTypeAndStatusFilters() {
        insert("MAINTENANCE", "ACTIVE", "2027-01-01", "2027-01-02",
            "2026-11-03T03:00:00Z", null);
        var cleaning = insert("CLEANING", "RELEASED", "2027-01-02", "2027-01-03",
            "2026-11-03T02:00:00Z", null);
        var manual = insert("MANUAL", "ACTIVE", "2027-01-03", "2027-01-04",
            "2026-11-03T01:00:00Z", null);

        var page = service.list(new InventoryBlockManagementService.Query(
            PROPERTY_ID, LocalDate.parse("2027-01-02"), LocalDate.parse("2027-01-04"),
            List.of("CLEANING", "MANUAL"), List.of("ACTIVE", "RELEASED"), 10, null));

        assertThat(page.nodes()).extracting(
            InventoryBlockManagementService.Block::inventoryBlockId)
            .containsExactly(cleaning.toString(), manual.toString());
        assertThat(page.nodes()).extracting(InventoryBlockManagementService.Block::reason)
            .allMatch(reason -> reason.startsWith("Management reason"));
    }

    private UUID insert(
        String type,
        String status,
        String start,
        String end,
        String updatedAt,
        String provider
    ) {
        var id = UUID.randomUUID();
        var releasedAt = status.equals("RELEASED") ? OffsetDateTime.parse(updatedAt) : null;
        jdbc.sql("""
                INSERT INTO inventory_blocks(
                    id, property_id, block_type, status, start_date,
                    end_date_exclusive, reason, channel_provider,
                    channel_external_block_id, released_at, created_at, updated_at
                ) VALUES (
                    :id, :propertyId, :type, :status, CAST(:startDate AS date),
                    CAST(:endDate AS date), :reason, :provider,
                    :externalId, :releasedAt, :updatedAt, :updatedAt
                )
                """)
            .param("id", id).param("propertyId", PROPERTY_ID).param("type", type)
            .param("status", status).param("startDate", start).param("endDate", end)
            .param("reason", "Management reason for " + id)
            .param("provider", provider, java.sql.Types.VARCHAR)
            .param("externalId", provider == null ? null : "opaque-provider-reference",
                java.sql.Types.VARCHAR)
            .param("releasedAt", releasedAt, java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
            .param("updatedAt", OffsetDateTime.parse(updatedAt))
            .update();
        return id;
    }
}
