package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.booking.application.PricingOverrideService.AuthorizationEvidence;
import com.vistay.booking.application.PricingOverrideService.Draft;
import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingQuoteCalculator.Command;
import com.vistay.booking.application.PricingQuoteCalculator.PriceComponentType;
import com.vistay.booking.application.PricingQuoteCalculator.Quote;
import com.vistay.booking.application.PricingScopePolicy.ScopeEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

class PricingOverrideIntegrationTest {
    private static final UUID PROFILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROPERTY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR_PROFILE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID BRANCH_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2020-01-01T00:00:00.123456789Z"), ZoneOffset.UTC);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("vistay_booking_override_test")
        .withUsername("vistay")
        .withPassword("vistay");

    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private PricingOverrideService service;
    private ProfileVersion profile;
    private Quote baseQuote;

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
        assertThat(result.targetSchemaVersion).isEqualTo("5");
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
            TRUNCATE TABLE pricing_manual_overrides, pricing_rule_versions,
                property_rate_profile_versions, command_idempotency, audit_events CASCADE
            """).update();
        insertProfile();
        profile = profile();
        baseQuote = new PricingQuoteCalculator().calculate(profile, quoteCommand(true));
        service = new PricingOverrideService(jdbc, JsonMapper.builder().build(), FIXED_CLOCK);
    }

    @Test
    void appliesExactTwentyPercentReductionAndPersistsImmutableEvidence() {
        var draft = draft("override-exact-20", baseQuote, 800);

        var applied = apply(draft, standardAuthorization());

        var evidence = applied.manualOverride();
        assertThat(evidence.beforeLodgingAmount()).isEqualTo("1000");
        assertThat(evidence.requestHash()).matches("^[0-9a-f]{64}$");
        assertThat(evidence.targetLodgingAmount()).isEqualTo("800");
        assertThat(evidence.adjustmentAmount()).isEqualTo("-200");
        assertThat(evidence.reductionBps()).isEqualTo(2_000);
        assertThat(evidence.largeApprovalRequired()).isFalse();
        assertThat(evidence.beforeCommissionAmount()).isEqualTo("50");
        assertThat(evidence.afterCommissionAmount()).isEqualTo("40");
        assertThat(evidence.beforeTotalAmount()).isEqualTo("1050");
        assertThat(evidence.afterTotalAmount()).isEqualTo("840");
        assertThat(evidence.overrideScopeType()).isEqualTo("ALL");
        assertThat(evidence.overridePermissionCode()).isEqualTo("pricing:override");
        assertThat(evidence.largeApprovalScopeType()).isNull();
        assertThat(evidence.largeApprovalPermissionCode()).isNull();
        assertThat(evidence.createdAt()).isEqualTo("2020-01-01T00:00:00.123456Z");
        assertThat(evidence.expiresAt()).isEqualTo("2020-01-01T00:15:00.123456Z");
        assertThat(applied.finalPrecedence()).containsExactly(
            "FIXED_DAILY_RATE:priority-asc,code-asc,id-asc:first-match-wins",
            "ADJUSTMENTS:priority-asc,code-asc,id-asc:percentage-basis-selected-daily-rate",
            "MANUAL_OVERRIDE:whole-stay-lodging-reduction",
            "COMMISSION:recalculated-after-manual-override"
        );
        assertThat(applied.finalComponents()).extracting(component -> component.type())
            .containsExactly(PriceComponentType.PROFILE_BASE_RATE,
                PriceComponentType.MANUAL_OVERRIDE, PriceComponentType.COMMISSION);
        assertThat(applied.finalLodgingAmount()).isEqualTo("800");
        assertThat(applied.finalCommissionAmount()).isEqualTo("40");
        assertThat(applied.finalTotalAmount()).isEqualTo("840");
        assertThat(service.require(evidence.id())).isEqualTo(applied);
        assertThat(count("pricing_manual_overrides")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(count("command_idempotency")).isEqualTo(1);

        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE pricing_manual_overrides SET reason = 'A different valid reason'
                WHERE id = :id
                """).param("id", evidence.id()).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("pricing manual overrides are immutable");
        assertThatThrownBy(() -> jdbc.sql(
                "DELETE FROM pricing_manual_overrides WHERE id = :id")
            .param("id", evidence.id()).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("pricing manual overrides are immutable");
    }

    @Test
    void oneVndReductionCanPersistWithZeroRoundedExplanatoryBasisPoints() {
        var highValueProfile = new ProfileVersion(
            PROFILE_ID, PROPERTY_ID, 1, "2026-01-01", "2027-01-01", "1000000",
            7_000, 5_500, "VND", "14:00", "12:00", 120, 15, 500, 2_000,
            "High value override rounding profile", ACTOR_ID, "override-rounding",
            "2026-10-30T00:00:00Z", List.of());
        var quote = new PricingQuoteCalculator().calculate(highValueProfile, quoteCommand(true));
        var draft = draft("override-one-vnd", quote, 999_999);

        var applied = transactions.execute(status -> service.apply(
            highValueProfile, quote, draft, standardAuthorization()));

        assertThat(applied.manualOverride().adjustmentAmount()).isEqualTo("-1");
        assertThat(applied.manualOverride().reductionBps()).isZero();
        assertThat(applied.manualOverride().largeApprovalRequired()).isFalse();
        assertThat(service.require(applied.manualOverride().id())).isEqualTo(applied);
    }

    @Test
    void greaterThanTwentyPercentRequiresAndPersistsSeparateApprovalEvidence() {
        var draft = draft("override-large-201", baseQuote, 799);

        assertCode(() -> apply(draft, standardAuthorization()),
            "LARGE_OVERRIDE_APPROVAL_REQUIRED");
        assertThat(count("pricing_manual_overrides")).isZero();
        assertThat(count("command_idempotency")).isZero();

        var applied = apply(draft, largeAuthorization());
        var evidence = applied.manualOverride();
        assertThat(evidence.reductionBps()).isEqualTo(2_010);
        assertThat(evidence.largeApprovalRequired()).isTrue();
        assertThat(evidence.largeApprovalAuthorizationVersion()).isEqualTo(12);
        assertThat(evidence.largeApprovalPermissionCode())
            .isEqualTo("pricing:approve-large-override");
        assertThat(evidence.largeApprovalScopeType()).isEqualTo("BRANCH");
        assertThat(evidence.largeApprovalPartitionId()).isEqualTo(BRANCH_ID);

        assertCode(() -> apply(draft("override-unneeded-large", baseQuote, 800),
            largeAuthorization()), "INVALID_OVERRIDE_AUTHORIZATION");
    }

    @Test
    void zeroTargetRequiresLargeApprovalAndRemovesCommissionComponent() {
        var draft = draft("override-zero-target", baseQuote, 0);
        assertCode(() -> apply(draft, standardAuthorization()),
            "LARGE_OVERRIDE_APPROVAL_REQUIRED");

        var applied = apply(draft, largeAuthorization());
        assertThat(applied.manualOverride().reductionBps()).isEqualTo(10_000);
        assertThat(applied.manualOverride().largeApprovalRequired()).isTrue();
        assertThat(applied.finalLodgingAmount()).isEqualTo("0");
        assertThat(applied.finalCommissionAmount()).isEqualTo("0");
        assertThat(applied.finalTotalAmount()).isEqualTo("0");
        assertThat(applied.finalComponents()).noneMatch(component ->
            component.type() == PriceComponentType.COMMISSION);
    }

    @Test
    void rejectsPartialLargeApprovalEvidence() {
        var draft = draft("override-partial-large", baseQuote, 799);
        var partial = new AuthorizationEvidence(
            ACTOR_ID, ACTOR_PROFILE_ID, 10, new ScopeEvidence("ALL", null), 12L, null);

        assertCode(() -> apply(draft, partial), "INVALID_OVERRIDE_AUTHORIZATION");
        assertThat(count("pricing_manual_overrides")).isZero();
        assertThat(count("command_idempotency")).isZero();
    }

    @Test
    void exactReplaySurvivesExpiryAndCommandCleanupUsingArtifactRequestHash() {
        var draft = draft("override-replay-fallback", baseQuote, 800);
        var first = apply(draft, standardAuthorization());
        assertThat(OffsetDateTime.parse(first.manualOverride().expiresAt()))
            .isBefore(OffsetDateTime.now(Clock.systemUTC()));

        assertThat(service.replay(ACTOR_ID, draft)).contains(first);
        assertThat(apply(draft, standardAuthorization())).isEqualTo(first);

        jdbc.sql("DELETE FROM command_idempotency WHERE idempotency_key = :key")
            .param("key", draft.idempotencyKey()).update();
        assertThat(service.replay(ACTOR_ID, draft)).contains(first);
        assertThat(apply(draft, standardAuthorization())).isEqualTo(first);
        assertThat(count("pricing_manual_overrides")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(1);
        assertThat(count("command_idempotency")).isZero();

        var changed = draft("override-replay-fallback", baseQuote, 799);
        assertCode(() -> service.replay(ACTOR_ID, changed), "IDEMPOTENCY_KEY_REUSED");
        assertCode(() -> apply(changed, largeAuthorization()), "IDEMPOTENCY_KEY_REUSED");
        assertCode(() -> service.replay(UUID.randomUUID(), draft), "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void concurrentExactRetriesCreateOneArtifactAndOneAuditEvent() throws Exception {
        var draft = draft("override-concurrent", baseQuote, 800);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return apply(draft, standardAuthorization());
            });
            var second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return apply(draft, standardAuthorization());
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var firstResult = first.get(20, TimeUnit.SECONDS);
            var secondResult = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(count("pricing_manual_overrides")).isEqualTo(1);
            assertThat(count("audit_events")).isEqualTo(1);
            assertThat(count("command_idempotency")).isEqualTo(1);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void replayRejectsCommandResourceIdThatPointsAtAnotherOverrideArtifact() {
        var firstDraft = draft("override-command-first", baseQuote, 800);
        var secondDraft = draft("override-command-second", baseQuote, 850);
        var first = apply(firstDraft, standardAuthorization());
        var second = apply(secondDraft, standardAuthorization());
        jdbc.sql("""
                UPDATE command_idempotency SET resource_id = :wrongId
                WHERE idempotency_key = :key
                """)
            .param("wrongId", second.manualOverride().id())
            .param("key", firstDraft.idempotencyKey())
            .update();

        assertCode(() -> service.replay(ACTOR_ID, firstDraft),
            "INVALID_STORED_PRICING_OVERRIDE");
        assertThat(first.manualOverride().id()).isNotEqualTo(second.manualOverride().id());
    }

    @Test
    void migrationEnforcesTwentyPercentBoundaryLargeEvidenceAndCommissionCeiling() {
        var exactId = insertRawOverride("raw-exact-20", 800, false, false, 500);
        assertThat(jdbc.sql("""
                SELECT large_approval_required FROM pricing_manual_overrides WHERE id = :id
                """).param("id", exactId).query(Boolean.class).single()).isFalse();

        assertRawConstraint("raw-large-without-evidence", 799, true, false, 500,
            "ck_pricing_override_large_scope");
        var largeId = insertRawOverride("raw-large-approved", 799, true, true, 500);
        assertThat(jdbc.sql("""
                SELECT large_approval_required FROM pricing_manual_overrides WHERE id = :id
                """).param("id", largeId).query(Boolean.class).single()).isTrue();
        assertRawConstraint("raw-large-flag-wrong", 799, false, false, 500,
            "ck_pricing_override_reduction");
        assertRawConstraint("raw-commission-over-max", 800, false, false, 2_001,
            "ck_pricing_override_amounts");
        assertThatThrownBy(() -> insertRawOverride(
            "raw-profile-property-mismatch", 800, false, false, 500, UUID.randomUUID()))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("fk_pricing_override_profile_property");

        var permissionEvidence = jdbc.sql("""
                SELECT override_permission_code, large_approval_permission_code
                FROM pricing_manual_overrides WHERE id = :id
                """).param("id", largeId).query(PermissionEvidence.class).single();
        assertThat(permissionEvidence.overridePermissionCode()).isEqualTo("pricing:override");
        assertThat(permissionEvidence.largeApprovalPermissionCode())
            .isEqualTo("pricing:approve-large-override");
    }

    @Test
    void handlesNonStaffQuoteAndRejectsStaleSubjectTargetMetadataAndAuthorizationFailures() {
        assertCode(() -> apply(new Draft(
                "override-stale-quote", baseQuoteCommand(), "b".repeat(64), 800,
                "Valid stale quote reason", "override-stale"), standardAuthorization()),
            "STALE_BASE_QUOTE");

        var mismatchedCommand = new Command(PROFILE_ID, UUID.randomUUID(),
            LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), 1,
            "ADMIN_PORTAL", true, null);
        assertCode(() -> apply(new Draft(
                "override-wrong-subject", mismatchedCommand, baseQuote.fingerprint(), 800,
                "Valid subject mismatch reason", "override-subject"), standardAuthorization()),
            "PRICING_OVERRIDE_SUBJECT_MISMATCH");

        var customerQuote = new PricingQuoteCalculator().calculate(profile, quoteCommand(false));
        var customerApplied = apply(draft("override-non-staff", customerQuote, 800),
            standardAuthorization());
        assertThat(customerApplied.baseQuote().staffCreated()).isFalse();
        assertThat(customerApplied.baseQuote().channel()).isEqualTo("CUSTOMER_WEB");
        assertThat(customerApplied.manualOverride().commissionBps()).isZero();
        assertThat(customerApplied.finalCommissionAmount()).isEqualTo("0");
        assertThat(customerApplied.finalTotalAmount()).isEqualTo("800");
        assertThat(customerApplied.finalComponents()).noneMatch(component ->
            component.type() == PriceComponentType.COMMISSION);
        assertCode(() -> apply(draft("override-target-equal", baseQuote, 1_000),
            standardAuthorization()), "INVALID_OVERRIDE_TARGET");
        assertCode(() -> apply(draft("override-target-negative", baseQuote, -1),
            standardAuthorization()), "INVALID_OVERRIDE_TARGET");
        assertCode(() -> apply(draft("override-invalid-scope", baseQuote, 800),
            new AuthorizationEvidence(ACTOR_ID, ACTOR_PROFILE_ID, 1,
                new ScopeEvidence("CITY", BRANCH_ID), null, null)),
            "INVALID_OVERRIDE_AUTHORIZATION");

        assertCode(() -> service.replay(ACTOR_ID, new Draft(
            "short", baseQuoteCommand(), baseQuote.fingerprint(), 800,
            "Valid override reason", "override-invalid-key")), "INVALID_IDEMPOTENCY_KEY");
        assertCode(() -> service.replay(ACTOR_ID, new Draft(
            "override-invalid-fingerprint", baseQuoteCommand(), "INVALID", 800,
            "Valid override reason", "override-invalid-fingerprint")),
            "INVALID_BASE_QUOTE_FINGERPRINT");
        assertCode(() -> service.replay(ACTOR_ID, new Draft(
            "override-invalid-reason", baseQuoteCommand(), baseQuote.fingerprint(), 800,
            " padded valid reason ", "override-invalid-reason")), "INVALID_OVERRIDE_REASON");
        assertCode(() -> service.replay(ACTOR_ID, new Draft(
            "override-invalid-correlation", baseQuoteCommand(), baseQuote.fingerprint(), 800,
            "Valid override reason", " ")), "INVALID_OVERRIDE_CORRELATION");
    }

    @Test
    void missingOverrideIsReportedExplicitly() {
        assertCode(() -> service.require(UUID.randomUUID()), "PRICING_OVERRIDE_NOT_FOUND");
    }

    private PricingOverrideService.AppliedOverride apply(
        Draft draft,
        AuthorizationEvidence authorization
    ) {
        return transactions.execute(status -> service.apply(profile, quoteFor(draft), draft,
            authorization));
    }

    private Quote quoteFor(Draft draft) {
        if (PROFILE_ID.equals(draft.quote().rateProfileVersionId())
            && PROPERTY_ID.equals(draft.quote().propertyId())) {
            return new PricingQuoteCalculator().calculate(profile, draft.quote());
        }
        return baseQuote;
    }

    private void assertCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private void assertRawConstraint(String key, long target, boolean large,
        boolean includeLargeEvidence, int commissionBps, String constraint) {
        assertThatThrownBy(() -> insertRawOverride(
            key, target, large, includeLargeEvidence, commissionBps))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining(constraint);
    }

    private UUID insertRawOverride(String key, long target, boolean large,
        boolean includeLargeEvidence, int commissionBps) {
        return insertRawOverride(key, target, large, includeLargeEvidence, commissionBps,
            PROPERTY_ID);
    }

    private UUID insertRawOverride(String key, long target, boolean large,
        boolean includeLargeEvidence, int commissionBps, UUID propertyId) {
        long before = 1_000;
        long adjustment = target - before;
        int reduction = Math.toIntExact(((before - target) * 10_000 + before / 2) / before);
        long beforeCommission = (before * commissionBps + 5_000) / 10_000;
        long afterCommission = (target * commissionBps + 5_000) / 10_000;
        var createdAt = OffsetDateTime.parse("2026-10-30T00:00:00Z");
        return jdbc.sql("""
                INSERT INTO pricing_manual_overrides(
                    idempotency_key, request_hash, property_id, rate_profile_version_id,
                    base_quote_fingerprint, calculation_version,
                    quote_input_snapshot, base_quote_snapshot, applied_override_snapshot,
                    before_lodging_amount, target_lodging_amount, adjustment_amount,
                    reduction_bps, commission_bps, before_commission_amount,
                    after_commission_amount, before_total_amount, after_total_amount,
                    currency, large_approval_required, actor_admin_account_id,
                    actor_admin_profile_id, authorization_version, override_permission_code,
                    override_scope_type, override_partition_id,
                    large_approval_authorization_version, large_approval_permission_code,
                    large_approval_scope_type, large_approval_partition_id,
                    reason, correlation_id, quote_ttl_minutes, created_at, expires_at
                ) VALUES (
                    :key, repeat('b', 64), :propertyId, :profileId,
                    repeat('a', 64), 'PRICING_V1', '{}'::jsonb, '{}'::jsonb, '{}'::jsonb,
                    :before, :target, :adjustment, :reduction, :commissionBps,
                    :beforeCommission, :afterCommission, :beforeTotal, :afterTotal,
                    'VND', :large, :actorId, :actorProfileId, 1, 'pricing:override',
                    'ALL', NULL,
                    CASE WHEN :includeLarge THEN 2::bigint ELSE NULL END,
                    CASE WHEN :includeLarge THEN 'pricing:approve-large-override' ELSE NULL END,
                    CASE WHEN :includeLarge THEN 'ALL' ELSE NULL END, NULL,
                    'Raw pricing override evidence', :correlation, 15, :createdAt,
                    :createdAt + interval '15 minutes'
                ) RETURNING id
                """)
            .param("key", key)
            .param("propertyId", propertyId)
            .param("profileId", PROFILE_ID)
            .param("before", before)
            .param("target", target)
            .param("adjustment", adjustment)
            .param("reduction", reduction)
            .param("commissionBps", commissionBps)
            .param("beforeCommission", beforeCommission)
            .param("afterCommission", afterCommission)
            .param("beforeTotal", before + beforeCommission)
            .param("afterTotal", target + afterCommission)
            .param("large", large)
            .param("actorId", ACTOR_ID)
            .param("actorProfileId", ACTOR_PROFILE_ID)
            .param("includeLarge", includeLargeEvidence)
            .param("correlation", key)
            .param("createdAt", createdAt)
            .query(UUID.class)
            .single();
    }

    private Draft draft(String key, Quote quote, long target) {
        return new Draft(key, commandFrom(quote), quote.fingerprint(), target,
            "Approved lodging reduction for guest recovery", "corr-" + key);
    }

    private Command commandFrom(Quote quote) {
        return new Command(quote.rateProfileVersionId(), quote.propertyId(),
            LocalDate.parse(quote.checkInDate()), LocalDate.parse(quote.checkOutDate()),
            quote.guestCount(), quote.channel(), quote.staffCreated(),
            quote.requestedCommissionBps());
    }

    private Command baseQuoteCommand() {
        return quoteCommand(true);
    }

    private Command quoteCommand(boolean staffCreated) {
        return new Command(PROFILE_ID, PROPERTY_ID, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2), 1,
            staffCreated ? "ADMIN_PORTAL" : "CUSTOMER_WEB", staffCreated, null);
    }

    private AuthorizationEvidence standardAuthorization() {
        return new AuthorizationEvidence(ACTOR_ID, ACTOR_PROFILE_ID, 10,
            new ScopeEvidence("ALL", null), null, null);
    }

    private AuthorizationEvidence largeAuthorization() {
        return new AuthorizationEvidence(ACTOR_ID, ACTOR_PROFILE_ID, 10,
            new ScopeEvidence("ALL", null), 12L,
            new ScopeEvidence("BRANCH", BRANCH_ID));
    }

    private void insertProfile() {
        jdbc.sql("""
                INSERT INTO property_rate_profile_versions(
                    id, property_id, version, effective_from, effective_to_exclusive,
                    base_daily_rate, medium_daily_factor_bps, long_daily_factor_bps,
                    commission_default_bps, commission_max_bps, reason,
                    authored_by_admin_account_id
                ) VALUES (
                    :id, :propertyId, 1, DATE '2026-01-01', DATE '2027-01-01',
                    1000, 7000, 5500, 500, 2000,
                    'Profile for manual override integration tests', :actorId
                )
                """)
            .param("id", PROFILE_ID)
            .param("propertyId", PROPERTY_ID)
            .param("actorId", ACTOR_ID)
            .update();
    }

    private ProfileVersion profile() {
        return new ProfileVersion(
            PROFILE_ID, PROPERTY_ID, 1, "2026-01-01", "2027-01-01", "1000",
            7_000, 5_500, "VND", "14:00", "12:00", 120, 15, 500, 2_000,
            "Profile for manual override integration tests", ACTOR_ID,
            "override-integration", "2026-10-30T00:00:00Z", List.of()
        );
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    record PermissionEvidence(
        String overridePermissionCode,
        String largeApprovalPermissionCode
    ) {}
}
