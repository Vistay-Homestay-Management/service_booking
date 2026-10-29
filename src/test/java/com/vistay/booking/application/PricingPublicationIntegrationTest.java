package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.booking.adapter.out.messaging.OutboxPublisher;
import com.vistay.booking.application.PricingOverrideService.AppliedOverride;
import com.vistay.booking.application.PricingPolicyValidator.Draft;
import com.vistay.booking.application.PricingPublicationService.ActiveProfile;
import com.vistay.booking.application.PricingPublicationService.AuthorizationEvidence;
import com.vistay.booking.application.PricingPublicationService.Publication;
import com.vistay.booking.application.PricingQuoteSnapshotService.Input;
import com.vistay.booking.application.PricingQuoteSnapshotService.QuoteSnapshot;
import com.vistay.booking.application.PricingScopePolicy.ScopeEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PricingPublicationIntegrationTest {
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR_PROFILE =
        UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String CORRELATION_ID =
        "77777777-7777-4777-8777-777777777777";
    private static final LocalDate NOVEMBER = LocalDate.of(2026, 11, 1);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        .withDatabaseName("vistay_booking_publication_test")
        .withUsername("vistay")
        .withPassword("vistay");

    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static ObjectMapper json;
    private MutableClock clock;
    private PricingPolicyService policies;
    private PricingPublicationService publications;
    private PricingQuoteCalculator calculator;
    private PricingOverrideService overrides;
    private PricingQuoteSnapshotService snapshots;

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
        assertThat(result.targetSchemaVersion).isEqualTo("6");
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = JsonMapper.builder().build();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("""
            TRUNCATE TABLE booking_quotes, pricing_profile_publications,
                pricing_manual_overrides, pricing_rule_versions,
                property_rate_profile_versions, command_idempotency,
                audit_events, outbox_events CASCADE
            """).update();
        clock = new MutableClock(Instant.parse("2026-10-30T03:00:00Z"));
        policies = new PricingPolicyService(jdbc, json, new PricingPolicyValidator());
        calculator = new PricingQuoteCalculator();
        overrides = new PricingOverrideService(jdbc, json, clock);
        publications = new PricingPublicationService(jdbc, json, policies, clock);
        snapshots = new PricingQuoteSnapshotService(
            jdbc, json, publications, calculator, overrides, clock);
    }

    @Test
    void publishesWithCanonicalFingerprintAndReplaysWithoutDuplicateEvidence() throws Exception {
        var propertyId = UUID.randomUUID();
        var profile = createProfile("author-profile-01", propertyId, NOVEMBER, 1_000_000);
        var preview = publications.preview(profile.id());
        assertThat(preview.profileFingerprint()).hasSize(64);
        assertThat(preview.currentPublication()).isNull();
        assertThat(preview.published()).isFalse();

        var command = publicationDraft("publish-profile-01", profile.id(),
            preview.profileFingerprint(), null, "Publish initial property pricing");
        var first = publish(command, profile);
        var replay = publish(command, profile);

        assertThat(replay).isEqualTo(first);
        assertThat(first.snapshotSchemaVersion()).isEqualTo("PRICING_PUBLICATION_V1");
        assertThat(first.publicationRevision()).isEqualTo(1);
        assertThat(first.previousPublicationId()).isNull();
        assertThat(first.permissionCode()).isEqualTo("pricing:approve");
        assertThat(first.scopeType()).isEqualTo("ALL");
        assertThat(publications.preview(profile.id()).published()).isTrue();
        assertThat(count("pricing_profile_publications")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT event_type FROM outbox_events")
            .query(String.class).single()).isEqualTo("vistay.booking.rate-profile-published");
        assertThat(jdbc.sql("SELECT aggregate_id FROM outbox_events")
            .query(UUID.class).single()).isEqualTo(propertyId);
        assertThat(jdbc.sql("SELECT aggregate_version FROM outbox_events")
            .query(Long.class).single()).isEqualTo(1);
        var eventPayload = json.readTree(jdbc.sql("SELECT payload::text FROM outbox_events")
            .query(String.class).single());
        assertThat(eventPayload.get("publicationId").asString())
            .isEqualTo(first.id().toString());
        assertThat(eventPayload.get("propertyId").asString()).isEqualTo(propertyId.toString());
        assertThat(eventPayload.get("publicationRevision").asLong()).isEqualTo(1);
        assertThat(eventPayload.get("previousPublicationId").isNull()).isTrue();
        assertThat(eventPayload.get("snapshotSchemaVersion").asString())
            .isEqualTo("PRICING_PUBLICATION_V1");

        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(anyString(), anyString(), anyString()))
            .thenReturn(CompletableFuture.completedFuture(null));
        new OutboxPublisher(jdbc, kafka, json, 1,
            "vistay.booking.rate-profile-published.v1").publishPending();
        var envelope = ArgumentCaptor.forClass(String.class);
        verify(kafka).send(eq("vistay.booking.rate-profile-published.v1"),
            eq(propertyId.toString()), envelope.capture());
        var publishedEnvelope = json.readTree(envelope.getValue());
        assertThat(publishedEnvelope.get("type").asString())
            .isEqualTo("vistay.booking.rate-profile-published");
        assertThat(publishedEnvelope.get("aggregateId").asString())
            .isEqualTo(propertyId.toString());
        assertThat(publishedEnvelope.get("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT published_at IS NOT NULL FROM outbox_events")
            .query(Boolean.class).single()).isTrue();
    }

    @Test
    void stalePublicationHeadIsRejectedAndActiveSelectionIsDeterministic() {
        var propertyId = UUID.randomUUID();
        var firstProfile = createProfile(
            "author-profile-02", propertyId, NOVEMBER, 1_000_000);
        var secondProfile = createProfile(
            "author-profile-03", propertyId, NOVEMBER.plusDays(10), 1_200_000);
        var first = publish(publicationDraft("publish-profile-02", firstProfile.id(),
            publications.profileFingerprint(firstProfile), null, "Publish first price window"),
            firstProfile);

        assertThatThrownBy(() -> publish(publicationDraft(
                "publish-profile-03", secondProfile.id(),
                publications.profileFingerprint(secondProfile), null,
                "Publish from a stale approval preview"), secondProfile))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("STALE_PRICING_PUBLICATION_HEAD"));

        var second = publish(publicationDraft(
            "publish-profile-04", secondProfile.id(),
            publications.profileFingerprint(secondProfile), first.id(),
            "Publish the newer effective price"), secondProfile);
        assertThat(second.publicationRevision()).isEqualTo(2);
        assertThat(second.previousPublicationId()).isEqualTo(first.id());
        ActiveProfile beforeSecondWindow = publications.active(propertyId,
            NOVEMBER.plusDays(2), NOVEMBER.plusDays(4));
        ActiveProfile insideSecondWindow = publications.active(propertyId,
            NOVEMBER.plusDays(12), NOVEMBER.plusDays(14));

        assertThat(beforeSecondWindow.publication().id()).isEqualTo(first.id());
        assertThat(insideSecondWindow.publication().id()).isEqualTo(second.id());
        assertThat(insideSecondWindow.profile().baseDailyRate()).isEqualTo("1200000");
    }

    @Test
    void concurrentApprovalsAgainstOneHeadAllowExactlyOneSuccess() throws Exception {
        var propertyId = UUID.randomUUID();
        var base = createProfile("author-profile-04", propertyId, NOVEMBER, 1_000_000);
        var basePublication = publish(publicationDraft(
            "publish-profile-05", base.id(), publications.profileFingerprint(base), null,
            "Publish concurrency baseline"), base);
        var candidateA = createProfile(
            "author-profile-05", propertyId, NOVEMBER.plusDays(10), 1_100_000);
        var candidateB = createProfile(
            "author-profile-06", propertyId, NOVEMBER.plusDays(20), 1_200_000);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> attemptPublish(ready, start,
                publicationDraft("publish-profile-06", candidateA.id(),
                    publications.profileFingerprint(candidateA), basePublication.id(),
                    "Publish concurrent candidate A"), candidateA));
            var second = executor.submit(() -> attemptPublish(ready, start,
                publicationDraft("publish-profile-07", candidateB.id(),
                    publications.profileFingerprint(candidateB), basePublication.id(),
                    "Publish concurrent candidate B"), candidateB));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            var outcomes = List.of(first.get(20, TimeUnit.SECONDS),
                second.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("PUBLISHED",
                "STALE_PRICING_PUBLICATION_HEAD");
            assertThat(count("pricing_profile_publications")).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reconcilesARevisionGapInAscendingPropertyScopedPages() {
        var propertyId = UUID.randomUUID();
        var firstProfile = createProfile(
            "author-history-01", propertyId, NOVEMBER, 1_000_000);
        var first = publish(publicationDraft(
            "publish-history-01", firstProfile.id(),
            publications.profileFingerprint(firstProfile), null,
            "Publish reconciliation revision one"), firstProfile);
        var secondProfile = createProfile(
            "author-history-02", propertyId, NOVEMBER.plusDays(5), 1_100_000);
        var second = publish(publicationDraft(
            "publish-history-02", secondProfile.id(),
            publications.profileFingerprint(secondProfile), first.id(),
            "Publish reconciliation revision two"), secondProfile);
        var thirdProfile = createProfile(
            "author-history-03", propertyId, NOVEMBER.plusDays(10), 1_200_000);
        var third = publish(publicationDraft(
            "publish-history-03", thirdProfile.id(),
            publications.profileFingerprint(thirdProfile), second.id(),
            "Publish reconciliation revision three"), thirdProfile);

        var otherProperty = UUID.randomUUID();
        var unrelatedProfile = createProfile(
            "author-history-04", otherProperty, NOVEMBER, 9_000_000);
        publish(publicationDraft(
            "publish-history-04", unrelatedProfile.id(),
            publications.profileFingerprint(unrelatedProfile), null,
            "Publish another property revision"), unrelatedProfile);

        var firstPage = publications.reconcile(propertyId, 0, 2);
        assertThat(firstPage.currentHead()).isEqualTo(third);
        assertThat(firstPage.publications()).extracting(Publication::publicationRevision)
            .containsExactly(1L, 2L);
        assertThat(firstPage.publications()).extracting(Publication::propertyId)
            .containsOnly(propertyId);
        assertThat(firstPage.publications().getFirst().profile()).isEqualTo(firstProfile);
        assertThat(firstPage.nextAfterPublicationRevision()).isEqualTo(2);
        assertThat(firstPage.hasMore()).isTrue();

        var continuation = publications.reconcile(
            propertyId, firstPage.nextAfterPublicationRevision(), 2);
        assertThat(continuation.currentHead()).isEqualTo(third);
        assertThat(continuation.publications()).containsExactly(third);
        assertThat(continuation.nextAfterPublicationRevision()).isEqualTo(3);
        assertThat(continuation.hasMore()).isFalse();

        var caughtUp = publications.reconcile(propertyId,
            continuation.nextAfterPublicationRevision(), 2);
        assertThat(caughtUp.publications()).isEmpty();
        assertThat(caughtUp.nextAfterPublicationRevision()).isEqualTo(3);
        assertThat(caughtUp.currentHead()).isEqualTo(third);
    }

    @Test
    void createsImmutablePublishedQuoteSnapshotAndReplaysExactArtifact() {
        var propertyId = UUID.randomUUID();
        var profile = createProfile("author-profile-07", propertyId, NOVEMBER, 1_000_000);
        publish(publicationDraft("publish-profile-08", profile.id(),
            publications.profileFingerprint(profile), null, "Publish snapshot pricing"), profile);
        var input = quoteInput(propertyId);
        var preview = snapshots.preview(input);
        var draft = snapshotDraft("snapshot-quote-01", input, preview.baseQuote(), null,
            "Persist published quote snapshot");

        var first = createSnapshot(draft);
        var replay = createSnapshot(draft);

        assertThat(replay).isEqualTo(first);
        assertThat(first.baseQuote().fingerprint()).isEqualTo(preview.baseQuote().fingerprint());
        assertThat(first.finalQuote().manualOverride()).isNull();
        assertThat(first.finalQuote().totalAmount()).isEqualTo("2000000");
        assertThat(first.permissionCode()).isEqualTo("pricing:read");
        assertThat(count("booking_quotes")).isEqualTo(1);
        assertThat(count("audit_events")).isEqualTo(3);

        jdbc.sql("UPDATE booking_quotes SET status = 'USED' WHERE id = :id")
            .param("id", first.id()).update();
        assertThatThrownBy(() -> jdbc.sql("""
                UPDATE booking_quotes SET total_amount = total_amount + 1 WHERE id = :id
                """).param("id", first.id()).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("pricing quote evidence is immutable");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM booking_quotes WHERE id = :id")
                .param("id", first.id()).update())
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("cannot be deleted");
    }

    @Test
    void rejectsStalePreviewAfterNewPublicationAndPreservesExistingSnapshot() {
        var propertyId = UUID.randomUUID();
        var firstProfile = createProfile(
            "author-profile-08", propertyId, NOVEMBER, 1_000_000);
        var firstPublication = publish(publicationDraft(
            "publish-profile-09", firstProfile.id(), publications.profileFingerprint(firstProfile),
            null, "Publish original quote pricing"), firstProfile);
        var input = quoteInput(propertyId);
        var oldPreview = snapshots.preview(input);
        var persisted = createSnapshot(snapshotDraft("snapshot-quote-02", input,
            oldPreview.baseQuote(), null, "Persist original historical quote"));

        var secondProfile = createProfile(
            "author-profile-09", propertyId, NOVEMBER.plusDays(1), 1_300_000);
        publish(publicationDraft("publish-profile-10", secondProfile.id(),
            publications.profileFingerprint(secondProfile), firstPublication.id(),
            "Publish replacement quote pricing"), secondProfile);

        assertThatThrownBy(() -> createSnapshot(snapshotDraft(
                "snapshot-quote-03", input, oldPreview.baseQuote(), null,
                "Reject stale historical preview")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("STALE_PUBLISHED_PRICING"));
        var historical = snapshots.requireSnapshot(persisted.id());
        assertThat(historical.rateProfileVersionId()).isEqualTo(firstProfile.id());
        assertThat(historical.finalQuote().totalAmount()).isEqualTo("2000000");
    }

    @Test
    void consumesExactUnexpiredOverrideOnlyOnce() {
        var propertyId = UUID.randomUUID();
        var profile = createProfile("author-profile-10", propertyId, NOVEMBER, 1_000_000);
        publish(publicationDraft("publish-profile-11", profile.id(),
            publications.profileFingerprint(profile), null, "Publish override pricing"), profile);
        var input = quoteInput(propertyId);
        var preview = snapshots.preview(input);
        var override = createOverride("override-use-01", profile, preview.baseQuote(), 1_600_000);

        var snapshot = createSnapshot(snapshotDraft("snapshot-quote-04", input,
            preview.baseQuote(), override.manualOverride().id(),
            "Persist quote with approved override"));

        assertThat(snapshot.manualOverrideId()).isEqualTo(override.manualOverride().id());
        assertThat(snapshot.finalQuote().lodgingAmount()).isEqualTo("1600000");
        assertThat(snapshot.finalQuote().totalAmount()).isEqualTo("1600000");
        assertThat(snapshot.finalQuote().manualOverride().reason())
            .isEqualTo("Authorize quote price reduction");
        assertThatThrownBy(() -> createSnapshot(snapshotDraft(
                "snapshot-quote-05", input, preview.baseQuote(),
                override.manualOverride().id(), "Try to consume override twice")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(
                    "PRICING_OVERRIDE_ALREADY_CONSUMED"));
    }

    @Test
    void rejectsExpiredOrMismatchedOverride() {
        var propertyId = UUID.randomUUID();
        var profile = createProfile("author-profile-11", propertyId, NOVEMBER, 1_000_000);
        publish(publicationDraft("publish-profile-12", profile.id(),
            publications.profileFingerprint(profile), null, "Publish expiry pricing"), profile);
        var input = quoteInput(propertyId);
        var preview = snapshots.preview(input);
        var expired = createOverride("override-use-02", profile, preview.baseQuote(), 1_600_000);
        clock.advanceSeconds(16 * 60L);

        assertThatThrownBy(() -> createSnapshot(snapshotDraft(
                "snapshot-quote-06", input, preview.baseQuote(),
                expired.manualOverride().id(), "Reject expired override usage")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("PRICING_OVERRIDE_EXPIRED"));

        clock.set(Instant.parse("2026-10-30T03:00:00Z"));
        var otherInput = new Input(propertyId, NOVEMBER.plusDays(3), NOVEMBER.plusDays(5),
            1, "ADMIN_PORTAL", false, null);
        var otherPreview = snapshots.preview(otherInput);
        assertThatThrownBy(() -> createSnapshot(snapshotDraft(
                "snapshot-quote-07", otherInput, otherPreview.baseQuote(),
                expired.manualOverride().id(), "Reject mismatched override usage")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("PRICING_OVERRIDE_MISMATCH"));
    }

    @Test
    void requiresPublishedPricingForPreview() {
        var propertyId = UUID.randomUUID();
        createProfile("author-profile-12", propertyId, NOVEMBER, 1_000_000);

        assertThatThrownBy(() -> snapshots.preview(quoteInput(propertyId)))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("ACTIVE_PRICING_NOT_FOUND"));
    }

    private PricingPolicyService.ProfileVersion createProfile(
        String key,
        UUID propertyId,
        LocalDate effectiveFrom,
        long baseRate
    ) {
        var draft = new Draft(key, propertyId, effectiveFrom, null, baseRate,
            7_000, 5_500, LocalTime.of(14, 0), LocalTime.of(12, 0),
            120, 15, 500, 2_000, "Author versioned pricing for publication",
            "pricing-publication-test", List.of());
        return transactions.execute(status -> policies.createVersion(ACTOR, draft));
    }

    private PricingPublicationService.Draft publicationDraft(
        String key,
        UUID profileId,
        String fingerprint,
        UUID expectedHead,
        String reason
    ) {
        return new PricingPublicationService.Draft(key, profileId, fingerprint,
            expectedHead, reason, CORRELATION_ID);
    }

    private Publication publish(
        PricingPublicationService.Draft draft,
        PricingPolicyService.ProfileVersion profile
    ) {
        return transactions.execute(status -> publications.publish(draft, profile,
            new AuthorizationEvidence(ACTOR, ACTOR_PROFILE, 7, new ScopeEvidence("ALL", null))));
    }

    private String attemptPublish(
        CountDownLatch ready,
        CountDownLatch start,
        PricingPublicationService.Draft draft,
        PricingPolicyService.ProfileVersion profile
    ) throws Exception {
        ready.countDown();
        start.await();
        try {
            publish(draft, profile);
            return "PUBLISHED";
        } catch (PricingPolicyException expected) {
            return expected.code();
        }
    }

    private Input quoteInput(UUID propertyId) {
        return new Input(propertyId, NOVEMBER.plusDays(2), NOVEMBER.plusDays(4),
            1, "ADMIN_PORTAL", false, null);
    }

    private PricingQuoteSnapshotService.Draft snapshotDraft(
        String key,
        Input input,
        PricingQuoteCalculator.Quote quote,
        UUID overrideId,
        String reason
    ) {
        return new PricingQuoteSnapshotService.Draft(key, input,
            quote.rateProfileVersionId(), quote.fingerprint(), overrideId,
            reason, CORRELATION_ID);
    }

    private QuoteSnapshot createSnapshot(PricingQuoteSnapshotService.Draft draft) {
        return transactions.execute(status -> snapshots.create(draft,
            new PricingQuoteSnapshotService.AuthorizationEvidence(
                ACTOR, ACTOR_PROFILE, 8, new ScopeEvidence("ALL", null))));
    }

    private AppliedOverride createOverride(
        String key,
        PricingPolicyService.ProfileVersion profile,
        PricingQuoteCalculator.Quote baseQuote,
        long target
    ) {
        var command = new PricingQuoteCalculator.Command(profile.id(), profile.propertyId(),
            LocalDate.parse(baseQuote.checkInDate()), LocalDate.parse(baseQuote.checkOutDate()),
            baseQuote.guestCount(), baseQuote.channel(), baseQuote.staffCreated(),
            baseQuote.requestedCommissionBps());
        var draft = new PricingOverrideService.Draft(key, command, baseQuote.fingerprint(),
            target, "Authorize quote price reduction", "pricing-override-consumption-test");
        return transactions.execute(status -> overrides.apply(profile, baseQuote, draft,
            new PricingOverrideService.AuthorizationEvidence(
                ACTOR, ACTOR_PROFILE, 9, new ScopeEvidence("ALL", null), null, null)));
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        void advanceSeconds(long seconds) {
            instant.updateAndGet(value -> value.plusSeconds(seconds));
        }

        void set(Instant value) {
            instant.set(value);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
