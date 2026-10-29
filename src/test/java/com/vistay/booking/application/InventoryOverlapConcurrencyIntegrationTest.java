package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

class InventoryOverlapConcurrencyIntegrationTest {

    private static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>("postgres:18.6-alpine")
            .withDatabaseName("vistay_booking_inventory_overlap_test")
            .withUsername("vistay")
            .withPassword("vistay");
    private static final LocalDate START = LocalDate.of(2027, 3, 1);

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + separator + "currentSchema=app,extensions,public",
            POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema("app")
            .schemas("app")
            .locations("classpath:db/migration")
            .load()
            .migrate();
        jdbc = JdbcClient.create(dataSource);
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
    void committedWinnerMakesWaitingOverlapFailWithoutPartialRevision() throws Exception {
        var propertyId = UUID.randomUUID();
        var winnerId = UUID.randomUUID();
        var loserId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);

        try (var winner = transaction()) {
            insert(winner, winnerId, propertyId, "MANUAL", "ACTIVE",
                START, START.plusDays(3));
            Future<WriteOutcome> loser = executor.submit(() -> attemptInsert(
                attempted, loserId, propertyId, "CLEANING", "TENTATIVE",
                START.plusDays(1), START.plusDays(4)));

            assertWaiting(attempted, loser);
            winner.commit();

            assertThat(loser.get(5, TimeUnit.SECONDS))
                .isEqualTo(new WriteOutcome(false, "23P01"));
        } finally {
            executor.shutdownNow();
        }

        assertThat(blockIds(propertyId)).containsExactly(winnerId);
        assertThat(revision(propertyId)).isEqualTo(1);
        assertThat(totalBlocks()).isEqualTo(1);
    }

    @Test
    void rolledBackWinnerLetsWaitingOverlapCommitExactlyOnce() throws Exception {
        var propertyId = UUID.randomUUID();
        var rolledBackId = UUID.randomUUID();
        var committedId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);

        try (var winner = transaction()) {
            insert(winner, rolledBackId, propertyId, "OWNER_USE", "ACTIVE",
                START, START.plusDays(3));
            Future<WriteOutcome> waiter = executor.submit(() -> attemptInsert(
                attempted, committedId, propertyId, "MAINTENANCE", "ACTIVE",
                START.plusDays(1), START.plusDays(4)));

            assertWaiting(attempted, waiter);
            winner.rollback();

            assertThat(waiter.get(5, TimeUnit.SECONDS))
                .isEqualTo(new WriteOutcome(true, null));
        } finally {
            executor.shutdownNow();
        }

        assertThat(blockIds(propertyId)).containsExactly(committedId);
        assertThat(revision(propertyId)).isEqualTo(1);
        assertThat(totalBlocks()).isEqualTo(1);
    }

    @Test
    void adjacentHalfOpenRangesBothCommitAndAdvanceRevisionPerRow() throws Exception {
        var propertyId = UUID.randomUUID();
        var firstId = UUID.randomUUID();
        var adjacentId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);

        try (var first = transaction()) {
            insert(first, firstId, propertyId, "MANUAL", "ACTIVE",
                START, START.plusDays(2));
            Future<WriteOutcome> adjacent = executor.submit(() -> attemptInsert(
                attempted, adjacentId, propertyId, "CLEANING", "ACTIVE",
                START.plusDays(2), START.plusDays(5)));

            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            first.commit();
            assertThat(adjacent.get(5, TimeUnit.SECONDS))
                .isEqualTo(new WriteOutcome(true, null));
        } finally {
            executor.shutdownNow();
        }

        assertThat(blockIds(propertyId)).containsExactlyInAnyOrder(firstId, adjacentId);
        assertThat(revision(propertyId)).isEqualTo(2);
        assertThat(totalBlocks()).isEqualTo(2);
    }

    @Test
    void overlappingRangesOnDifferentPropertiesCommitIndependently() throws Exception {
        var firstProperty = UUID.randomUUID();
        var secondProperty = UUID.randomUUID();
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var executor = Executors.newSingleThreadExecutor();
        var attempted = new CountDownLatch(1);

        try (var first = transaction()) {
            insert(first, firstId, firstProperty, "MANUAL", "ACTIVE",
                START, START.plusDays(3));
            Future<WriteOutcome> independent = executor.submit(() -> attemptInsert(
                attempted, secondId, secondProperty, "MANUAL", "ACTIVE",
                START, START.plusDays(3)));

            assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(independent.get(5, TimeUnit.SECONDS))
                .isEqualTo(new WriteOutcome(true, null));
            first.commit();
        } finally {
            executor.shutdownNow();
        }

        assertThat(blockIds(firstProperty)).containsExactly(firstId);
        assertThat(blockIds(secondProperty)).containsExactly(secondId);
        assertThat(revision(firstProperty)).isEqualTo(1);
        assertThat(revision(secondProperty)).isEqualTo(1);
        assertThat(totalBlocks()).isEqualTo(2);
    }

    private static Connection transaction() throws SQLException {
        var connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }

    private static WriteOutcome attemptInsert(
        CountDownLatch attempted,
        UUID blockId,
        UUID propertyId,
        String type,
        String status,
        LocalDate start,
        LocalDate end
    ) {
        try (var connection = transaction()) {
            attempted.countDown();
            try {
                insert(connection, blockId, propertyId, type, status, start, end);
                connection.commit();
                return new WriteOutcome(true, null);
            } catch (SQLException failure) {
                connection.rollback();
                return new WriteOutcome(false, failure.getSQLState());
            }
        } catch (SQLException failure) {
            return new WriteOutcome(false, failure.getSQLState());
        }
    }

    private static void insert(
        Connection connection,
        UUID blockId,
        UUID propertyId,
        String type,
        String status,
        LocalDate start,
        LocalDate end
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO inventory_blocks(
                    id, property_id, block_type, status, start_date, end_date_exclusive
                ) VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, blockId);
            statement.setObject(2, propertyId);
            statement.setString(3, type);
            statement.setString(4, status);
            statement.setObject(5, start);
            statement.setObject(6, end);
            statement.executeUpdate();
        }
    }

    private static void assertWaiting(
        CountDownLatch attempted,
        Future<WriteOutcome> write
    ) throws Exception {
        assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> write.get(500, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
    }

    private static java.util.List<UUID> blockIds(UUID propertyId) {
        return jdbc.sql("""
                SELECT id FROM inventory_blocks
                WHERE property_id = :propertyId ORDER BY id
                """)
            .param("propertyId", propertyId)
            .query(UUID.class)
            .list();
    }

    private static long revision(UUID propertyId) {
        return jdbc.sql("""
                SELECT revision FROM inventory_property_revisions
                WHERE property_id = :propertyId
                """)
            .param("propertyId", propertyId)
            .query(Long.class)
            .optional()
            .orElse(0L);
    }

    private static long totalBlocks() {
        return jdbc.sql("SELECT count(*) FROM inventory_blocks")
            .query(Long.class)
            .single();
    }

    private record WriteOutcome(boolean committed, String sqlState) {}
}
