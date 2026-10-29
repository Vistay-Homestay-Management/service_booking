package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.json.JsonMapper;

class PricingAuthoringIntegrationTest {
    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate EFFECTIVE_TO = LocalDate.of(2027, 11, 1);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("vistay_booking_pricing_test")
        .withUsername("vistay")
        .withPassword("vistay");

    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private PricingPolicyService service;

    @BeforeAll
    static void migrate() {
        POSTGRES.start();
        var separator = POSTGRES.getJdbcUrl().contains("?") ? "&" : "?";
        var dataSource = new DriverManagerDataSource(
            POSTGRES.getJdbcUrl() + separator + "currentSchema=app,extensions,public",
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
        var result = Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema("app")
            .schemas("app")
            .locations("classpath:db/migration")
            .load()
            .migrate();
        assertThat(result.success).isTrue();
        assertThat(result.targetSchemaVersion).isEqualTo("4");
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("""
            TRUNCATE TABLE pricing_rule_versions, property_rate_profile_versions,
                command_idempotency, audit_events CASCADE
            """).update();
        service = new PricingPolicyService(
            jdbc, JsonMapper.builder().build(), new PricingPolicyValidator());
    }

    @Test
    void migrationStoresApprovedDefaultsAndRejectsMutation() {
        var profileId = insertMinimalProfile(UUID.randomUUID(), UUID.randomUUID(), 1);

        var defaults = jdbc.sql("""
            SELECT medium_daily_factor_bps, long_daily_factor_bps, currency,
                   check_in_time::text AS check_in_time,
                   check_out_time::text AS check_out_time,
                   turnover_minutes, quote_ttl_minutes,
                   commission_default_bps, commission_max_bps
            FROM property_rate_profile_versions WHERE id = :id
            """)
            .param("id", profileId)
            .query(ProfileDefaults.class)
            .single();
        assertThat(defaults).isEqualTo(new ProfileDefaults(
            7_000, 5_500, "VND", "14:00:00", "12:00:00", 120, 15, 500, 2_000));

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE property_rate_profile_versions SET base_daily_rate = 1200000
                WHERE id = :id
                """).param("id", profileId).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("pricing versions are immutable");
        assertThatThrownBy(() -> jdbc.sql(
                "DELETE FROM property_rate_profile_versions WHERE id = :id")
            .param("id", profileId).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("pricing versions are immutable");
    }

    @Test
    void migrationEnforcesRateCommissionAndVersionConstraints() {
        var propertyId = UUID.randomUUID();
        insertMinimalProfile(propertyId, UUID.randomUUID(), 1);

        assertConstraint("""
            INSERT INTO property_rate_profile_versions(
                property_id, version, effective_from, base_daily_rate,
                medium_daily_factor_bps, long_daily_factor_bps,
                reason, authored_by_admin_account_id
            ) VALUES (:propertyId, 2, DATE '2027-01-01', 1000000,
                6000, 7000, 'Invalid long stay factor', :actorId)
            """, propertyId, "ck_rate_profile_factors");
        assertConstraint("""
            INSERT INTO property_rate_profile_versions(
                property_id, version, effective_from, base_daily_rate,
                commission_default_bps, commission_max_bps,
                reason, authored_by_admin_account_id
            ) VALUES (:propertyId, 2, DATE '2027-01-01', 1000000,
                500, 2001, 'Invalid commission ceiling', :actorId)
            """, propertyId, "ck_rate_profile_commission");
        assertConstraint("""
            INSERT INTO property_rate_profile_versions(
                property_id, version, effective_from, base_daily_rate,
                reason, authored_by_admin_account_id
            ) VALUES (:propertyId, 1, DATE '2027-01-01', 1000000,
                'Duplicate version number', :actorId)
            """, propertyId, "ux_property_rate_profile_version");
    }

    @Test
    void sameIdempotencyKeyReplaysDurableProfileWithoutDuplicateAudit() {
        var actorId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var draft = validDraft("pricing-idempotency-1", propertyId, 1_000_000);

        var first = create(actorId, draft);
        var replay = create(actorId, draft);

        assertThat(replay).isEqualTo(first);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.propertyId()).isEqualTo(propertyId);
        assertThat(first.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.code()).isEqualTo("weekday-discount");
            assertThat(rule.daysOfWeek()).containsExactly(1, 2, 3, 4, 5);
            assertThat(rule.channels()).containsExactly("ADMIN_PORTAL");
        });
        assertThat(count("property_rate_profile_versions")).isEqualTo(1);
        assertThat(count("pricing_rule_versions")).isEqualTo(1);
        assertThat(count("command_idempotency")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void idempotencyKeyCannotBeReusedForChangedPricingData() {
        var actorId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        create(actorId, validDraft("pricing-reuse-1", propertyId, 1_000_000));

        assertThatThrownBy(() -> create(
            actorId, validDraft("pricing-reuse-1", propertyId, 1_100_000)))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(count("property_rate_profile_versions")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
    }

    @Test
    void assignsMonotonicVersionsPerProperty() {
        var actorId = UUID.randomUUID();
        var firstProperty = UUID.randomUUID();
        var secondProperty = UUID.randomUUID();

        var first = create(actorId, validDraft("pricing-sequence-1", firstProperty, 1_000_000));
        var second = create(actorId, validDraft("pricing-sequence-2", firstProperty, 1_100_000));
        var other = create(actorId, validDraft("pricing-sequence-3", secondProperty, 900_000));

        assertThat(first.version()).isEqualTo(1);
        assertThat(second.version()).isEqualTo(2);
        assertThat(other.version()).isEqualTo(1);
        assertThat(service.versions(firstProperty)).extracting(PricingPolicyService.ProfileVersion::version)
            .containsExactly(2, 1);
    }

    @Test
    void calculatesPersistedProfileWithoutWritingQuoteState() {
        var actorId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var profile = create(actorId,
            validDraft("pricing-calculate-1", propertyId, 1_000_000));
        var tableCounts = Map.of(
            "profiles", count("property_rate_profile_versions"),
            "rules", count("pricing_rule_versions"),
            "commands", count("command_idempotency"),
            "audits", count("audit_events")
        );

        var loaded = service.requireVersion(profile.id());
        var quote = new PricingQuoteCalculator().calculate(loaded,
            new PricingQuoteCalculator.Command(
                profile.id(), propertyId, LocalDate.of(2026, 11, 2),
                LocalDate.of(2026, 11, 4), 2, "ADMIN_PORTAL", false, null
            ));

        assertThat(quote.stayProduct()).isEqualTo(PricingQuoteCalculator.StayProduct.SHORT);
        assertThat(quote.lodgingBeforeAdjustments()).isEqualTo("2000000");
        assertThat(quote.discountAmount()).isEqualTo("100000");
        assertThat(quote.totalAmount()).isEqualTo("1900000");
        assertThat(quote.evaluatedRules()).hasSize(2).allSatisfy(evaluation ->
            assertThat(evaluation.reason()).isEqualTo("ACTION_APPLIED"));
        assertThat(count("property_rate_profile_versions")).isEqualTo(tableCounts.get("profiles"));
        assertThat(count("pricing_rule_versions")).isEqualTo(tableCounts.get("rules"));
        assertThat(count("command_idempotency")).isEqualTo(tableCounts.get("commands"));
        assertThat(count("audit_events")).isEqualTo(tableCounts.get("audits"));
    }

    @Test
    void serializesConcurrentVersionAuthorsForTheSameProperty() throws Exception {
        var actorId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return create(actorId,
                    validDraft("pricing-concurrent-1", propertyId, 1_000_000));
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return create(actorId,
                    validDraft("pricing-concurrent-2", propertyId, 1_100_000));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var versions = List.of(
                first.get(20, TimeUnit.SECONDS).version(),
                second.get(20, TimeUnit.SECONDS).version()
            );
            assertThat(versions).containsExactlyInAnyOrder(1, 2);
            assertThat(count("property_rate_profile_versions")).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private PricingPolicyService.ProfileVersion create(
        UUID actorId,
        PricingPolicyValidator.Draft draft
    ) {
        return transactions.execute(status -> service.createVersion(actorId, draft));
    }

    private PricingPolicyValidator.Draft validDraft(String key, UUID propertyId, long baseRate) {
        var rule = new PricingPolicyValidator.Rule(
            "weekday-discount", "Weekday discount", EFFECTIVE_FROM, EFFECTIVE_TO,
            100, List.of(1, 2, 3, 4, 5), List.of("ADMIN_PORTAL"),
            1, 29, 1, 4, "PERCENT_DISCOUNT", 500
        );
        return new PricingPolicyValidator.Draft(
            key, propertyId, EFFECTIVE_FROM, EFFECTIVE_TO, baseRate,
            7_000, 5_500, LocalTime.of(14, 0), LocalTime.of(12, 0),
            120, 15, 500, 2_000, "Create a versioned property pricing policy",
            "pricing-integration-test", List.of(rule)
        );
    }

    private UUID insertMinimalProfile(UUID propertyId, UUID actorId, int version) {
        return jdbc.sql("""
                INSERT INTO property_rate_profile_versions(
                    property_id, version, effective_from, base_daily_rate,
                    reason, authored_by_admin_account_id
                ) VALUES (
                    :propertyId, :version, DATE '2026-11-01', 1000000,
                    'Create an immutable pricing version', :actorId
                ) RETURNING id
                """)
            .param("propertyId", propertyId)
            .param("version", version)
            .param("actorId", actorId)
            .query(UUID.class)
            .single();
    }

    private void assertConstraint(String sql, UUID propertyId, String constraintName) {
        assertThatThrownBy(() -> jdbc.sql(sql)
            .param("propertyId", propertyId)
            .param("actorId", UUID.randomUUID())
            .update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(constraintName);
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    record ProfileDefaults(
        int mediumDailyFactorBps,
        int longDailyFactorBps,
        String currency,
        String checkInTime,
        String checkOutTime,
        int turnoverMinutes,
        int quoteTtlMinutes,
        int commissionDefaultBps,
        int commissionMaxBps
    ) {}
}
