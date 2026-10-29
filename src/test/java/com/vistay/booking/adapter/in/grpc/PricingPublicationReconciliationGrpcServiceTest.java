package com.vistay.booking.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingPolicyService.RuleVersion;
import com.vistay.booking.application.PricingPublicationService;
import com.vistay.booking.application.PricingPublicationService.Publication;
import com.vistay.booking.application.PricingPublicationService.PublicationHistory;
import com.vistay.booking.config.GrpcTrustBoundaryInterceptor;
import com.vistay.contracts.booking.v1.ReconcilePricingPublicationsRequest;
import com.vistay.contracts.booking.v1.ReconcilePricingPublicationsResponse;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingPublicationReconciliationGrpcServiceTest {
    private static final String FINANCE_CALLER = "spiffe://vistay/service/finance";
    private static final UUID PROPERTY_ID =
        UUID.fromString("20000000-0000-4000-8000-000000000002");

    @Test
    void returnsCurrentHeadAndOrderedImmutableProfileEvidenceForAllowedCaller() {
        var publications = mock(PricingPublicationService.class);
        var first = publication(1, null, 1_000_000);
        var second = publication(2, first.id(), 1_200_000);
        when(publications.reconcile(PROPERTY_ID, 1, 25)).thenReturn(
            new PublicationHistory(PROPERTY_ID, second, List.of(second), 2, false));
        var service = new PricingPublicationReconciliationGrpcService(
            publications, List.of(FINANCE_CALLER));
        var observer = new CapturingObserver<ReconcilePricingPublicationsResponse>();
        var request = ReconcilePricingPublicationsRequest.newBuilder()
            .setPropertyId(PROPERTY_ID.toString())
            .setAfterPublicationRevision(1)
            .setPageSize(25)
            .build();

        asCaller(FINANCE_CALLER,
            () -> service.reconcilePricingPublications(request, observer));

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getPropertyId()).isEqualTo(PROPERTY_ID.toString());
        assertThat(observer.value.getCurrentHeadPublicationRevision()).isEqualTo(2);
        assertThat(observer.value.getCurrentHeadPublicationId()).isEqualTo(
            second.id().toString());
        assertThat(observer.value.getNextAfterPublicationRevision()).isEqualTo(2);
        assertThat(observer.value.getHasMore()).isFalse();
        assertThat(observer.value.getPublicationsCount()).isEqualTo(1);
        var record = observer.value.getPublications(0);
        assertThat(record.getPublicationRevision()).isEqualTo(2);
        assertThat(record.getPreviousPublicationId()).isEqualTo(first.id().toString());
        assertThat(record.getProfileFingerprint()).isEqualTo("a".repeat(64));
        assertThat(record.getProfile().getBaseDailyRate()).isEqualTo("1200000");
        assertThat(record.getProfile().getRulesCount()).isEqualTo(1);
        assertThat(record.getProfile().getRules(0).getCode()).isEqualTo("short-stay-rate");
        assertThat(record.getProfile().getRules(0).hasMaximumGuests()).isFalse();
        verify(publications).reconcile(PROPERTY_ID, 1, 25);
    }

    @Test
    void deniesMissingOrNonPricingCallerBeforeReadingAnyProperty() {
        var publications = mock(PricingPublicationService.class);
        var service = new PricingPublicationReconciliationGrpcService(
            publications, List.of(FINANCE_CALLER));
        var request = ReconcilePricingPublicationsRequest.newBuilder()
            .setPropertyId(PROPERTY_ID.toString())
            .build();

        var missingIdentity = new CapturingObserver<ReconcilePricingPublicationsResponse>();
        service.reconcilePricingPublications(request, missingIdentity);
        assertThat(status(missingIdentity)).isEqualTo(Status.Code.UNAUTHENTICATED);

        var deniedCaller = new CapturingObserver<ReconcilePricingPublicationsResponse>();
        asCaller("spiffe://vistay/service/payment",
            () -> service.reconcilePricingPublications(request, deniedCaller));
        assertThat(status(deniedCaller)).isEqualTo(Status.Code.PERMISSION_DENIED);
        verifyNoInteractions(publications);
    }

    @Test
    void rejectsInvalidPropertyRevisionAndUnboundedPageBeforeTheReader() {
        var publications = mock(PricingPublicationService.class);
        var service = new PricingPublicationReconciliationGrpcService(
            publications, List.of(FINANCE_CALLER));

        for (var request : List.of(
            ReconcilePricingPublicationsRequest.newBuilder()
                .setPropertyId("not-a-uuid").build(),
            ReconcilePricingPublicationsRequest.newBuilder()
                .setPropertyId(PROPERTY_ID.toString())
                .setAfterPublicationRevision(-1).build(),
            ReconcilePricingPublicationsRequest.newBuilder()
                .setPropertyId(PROPERTY_ID.toString()).setPageSize(101).build())) {
            var observer = new CapturingObserver<ReconcilePricingPublicationsResponse>();
            asCaller(FINANCE_CALLER,
                () -> service.reconcilePricingPublications(request, observer));
            assertThat(status(observer)).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }
        verifyNoInteractions(publications);
    }

    @Test
    void appliesTheBoundedDefaultPageSizeAndPreservesEmptyHeadSemantics() {
        var publications = mock(PricingPublicationService.class);
        when(publications.reconcile(PROPERTY_ID, 0, 50)).thenReturn(
            new PublicationHistory(PROPERTY_ID, null, List.of(), 0, false));
        var service = new PricingPublicationReconciliationGrpcService(
            publications, List.of(FINANCE_CALLER));
        var observer = new CapturingObserver<ReconcilePricingPublicationsResponse>();

        asCaller(FINANCE_CALLER, () -> service.reconcilePricingPublications(
            ReconcilePricingPublicationsRequest.newBuilder()
                .setPropertyId(PROPERTY_ID.toString()).build(), observer));

        assertThat(observer.error).isNull();
        assertThat(observer.value.getCurrentHeadPublicationRevision()).isZero();
        assertThat(observer.value.hasCurrentHeadPublicationId()).isFalse();
        assertThat(observer.value.getPublicationsList()).isEmpty();
        verify(publications).reconcile(PROPERTY_ID, 0, 50);
    }

    private static Publication publication(long revision, UUID previousId, long baseRate) {
        var profileId = UUID.randomUUID();
        var actorId = UUID.randomUUID();
        var actorProfileId = UUID.randomUUID();
        var profile = new ProfileVersion(
            profileId, PROPERTY_ID, Math.toIntExact(revision), "2026-11-01", null,
            Long.toString(baseRate), 7_000, 5_500, "VND", "14:00", "12:00",
            120, 15, 500, 2_000, "Immutable reconciliation profile", actorId,
            "77777777-7777-4777-8777-777777777777", "2026-10-30T03:00:00Z",
            List.of(new RuleVersion(UUID.randomUUID(), "short-stay-rate",
                "Short stay rate", "2026-11-01", null, 100, List.of(1, 2, 3),
                List.of("ADMIN_PORTAL"), 1, 29, 1, null,
                "FIXED_DAILY_RATE", Long.toString(baseRate))));
        return new Publication(UUID.randomUUID(), "publish-history-" + revision,
            "b".repeat(64), PROPERTY_ID, profileId, previousId, revision,
            "a".repeat(64), "PRICING_PUBLICATION_V1", actorId, actorProfileId,
            7, "pricing:approve", "ALL", null,
            "Publish immutable reconciliation profile",
            "77777777-7777-4777-8777-777777777777", "2026-10-30T03:00:00Z",
            profile);
    }

    private static void asCaller(String callerUri, Runnable action) {
        Context.current()
            .withValue(GrpcTrustBoundaryInterceptor.AUTHENTICATED_CALLER_URI, callerUri)
            .run(action);
    }

    private static Status.Code status(CapturingObserver<?> observer) {
        return ((StatusRuntimeException) observer.error).getStatus().getCode();
    }

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        private T value;
        private Throwable error;
        private boolean completed;

        @Override public void onNext(T value) { this.value = value; }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onCompleted() { this.completed = true; }
    }
}
