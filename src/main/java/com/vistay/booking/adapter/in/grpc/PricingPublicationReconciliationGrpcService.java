package com.vistay.booking.adapter.in.grpc;

import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingPolicyService.RuleVersion;
import com.vistay.booking.application.PricingPublicationService;
import com.vistay.booking.application.PricingPublicationService.Publication;
import com.vistay.booking.config.GrpcTrustBoundaryInterceptor;
import com.vistay.contracts.booking.v1.PricingPublicationRecord;
import com.vistay.contracts.booking.v1.PricingPublicationReconciliationServiceGrpc;
import com.vistay.contracts.booking.v1.PricingRateProfileSnapshot;
import com.vistay.contracts.booking.v1.PricingRuleSnapshot;
import com.vistay.contracts.booking.v1.ReconcilePricingPublicationsRequest;
import com.vistay.contracts.booking.v1.ReconcilePricingPublicationsResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService(interceptors = GrpcTrustBoundaryInterceptor.class)
public class PricingPublicationReconciliationGrpcService
    extends PricingPublicationReconciliationServiceGrpc
        .PricingPublicationReconciliationServiceImplBase {

    static final int DEFAULT_PAGE_SIZE = 50;
    static final int MAXIMUM_PAGE_SIZE = 100;

    private final PricingPublicationService publications;
    private final Set<String> allowedCallerUris;

    public PricingPublicationReconciliationGrpcService(
        PricingPublicationService publications,
        @Value("${vistay.grpc.pricing-reconciliation-allowed-caller-uris:"
            + "spiffe://vistay/service/finance}") List<String> allowedCallerUris
    ) {
        this.publications = publications;
        this.allowedCallerUris = Set.copyOf(allowedCallerUris);
    }

    @Override
    public void reconcilePricingPublications(
        ReconcilePricingPublicationsRequest request,
        StreamObserver<ReconcilePricingPublicationsResponse> responseObserver
    ) {
        var callerUri = GrpcTrustBoundaryInterceptor.AUTHENTICATED_CALLER_URI.get();
        if (callerUri == null) {
            fail(responseObserver, Status.UNAUTHENTICATED,
                "Authenticated mTLS caller identity is required");
            return;
        }
        if (!allowedCallerUris.contains(callerUri)) {
            fail(responseObserver, Status.PERMISSION_DENIED,
                "Caller is not allowed to reconcile pricing publications");
            return;
        }

        final UUID propertyId;
        try {
            propertyId = UUID.fromString(request.getPropertyId());
        } catch (IllegalArgumentException invalid) {
            fail(responseObserver, Status.INVALID_ARGUMENT, "property_id must be a UUID");
            return;
        }
        if (request.getAfterPublicationRevision() < 0) {
            fail(responseObserver, Status.INVALID_ARGUMENT,
                "after_publication_revision must not be negative");
            return;
        }
        int pageSize = request.getPageSize() == 0
            ? DEFAULT_PAGE_SIZE : request.getPageSize();
        if (pageSize < 1 || pageSize > MAXIMUM_PAGE_SIZE) {
            fail(responseObserver, Status.INVALID_ARGUMENT,
                "page_size must be zero or between 1 and 100");
            return;
        }

        try {
            var history = publications.reconcile(propertyId,
                request.getAfterPublicationRevision(), pageSize);
            var response = ReconcilePricingPublicationsResponse.newBuilder()
                .setPropertyId(propertyId.toString())
                .setNextAfterPublicationRevision(history.nextAfterPublicationRevision())
                .setHasMore(history.hasMore());
            if (history.currentHead() != null) {
                response
                    .setCurrentHeadPublicationRevision(
                        history.currentHead().publicationRevision())
                    .setCurrentHeadPublicationId(history.currentHead().id().toString());
            }
            history.publications().stream()
                .map(this::publicationRecord)
                .forEach(response::addPublications);
            responseObserver.onNext(response.build());
            responseObserver.onCompleted();
        } catch (PricingPolicyException invalidEvidence) {
            var status = invalidEvidence.code().startsWith("INVALID_STORED_")
                ? Status.DATA_LOSS : Status.INVALID_ARGUMENT;
            fail(responseObserver, status, invalidEvidence.getMessage());
        } catch (DataAccessException unavailable) {
            fail(responseObserver, Status.UNAVAILABLE,
                "Pricing publication reconciliation store is unavailable");
        }
    }

    private PricingPublicationRecord publicationRecord(Publication publication) {
        var record = PricingPublicationRecord.newBuilder()
            .setPublicationId(publication.id().toString())
            .setPropertyId(publication.propertyId().toString())
            .setRateProfileVersionId(publication.rateProfileVersionId().toString())
            .setPublicationRevision(publication.publicationRevision())
            .setProfileFingerprint(publication.profileFingerprint())
            .setSnapshotSchemaVersion(publication.snapshotSchemaVersion())
            .setPublishedAt(publication.publishedAt())
            .setProfile(profileSnapshot(publication.profile()));
        if (publication.previousPublicationId() != null) {
            record.setPreviousPublicationId(publication.previousPublicationId().toString());
        }
        return record.build();
    }

    private PricingRateProfileSnapshot profileSnapshot(ProfileVersion profile) {
        var snapshot = PricingRateProfileSnapshot.newBuilder()
            .setRateProfileVersionId(profile.id().toString())
            .setPropertyId(profile.propertyId().toString())
            .setProfileVersion(profile.version())
            .setEffectiveFrom(profile.effectiveFrom())
            .setBaseDailyRate(profile.baseDailyRate())
            .setMediumDailyFactorBps(profile.mediumDailyFactorBps())
            .setLongDailyFactorBps(profile.longDailyFactorBps())
            .setCurrency(profile.currency())
            .setCheckInTime(profile.checkInTime())
            .setCheckOutTime(profile.checkOutTime())
            .setTurnoverMinutes(profile.turnoverMinutes())
            .setQuoteTtlMinutes(profile.quoteTtlMinutes())
            .setCommissionDefaultBps(profile.commissionDefaultBps())
            .setCommissionMaxBps(profile.commissionMaxBps())
            .setAuthoringReason(profile.reason())
            .setAuthoredByAdminAccountId(profile.authoredByAdminAccountId().toString())
            .setAuthoringCorrelationId(profile.correlationId())
            .setAuthoredAt(profile.authoredAt());
        if (profile.effectiveToExclusive() != null) {
            snapshot.setEffectiveToExclusive(profile.effectiveToExclusive());
        }
        profile.rules().stream().map(this::ruleSnapshot).forEach(snapshot::addRules);
        return snapshot.build();
    }

    private PricingRuleSnapshot ruleSnapshot(RuleVersion rule) {
        var snapshot = PricingRuleSnapshot.newBuilder()
            .setRuleVersionId(rule.id().toString())
            .setCode(rule.code())
            .setName(rule.name())
            .setEffectiveFrom(rule.effectiveFrom())
            .setPriority(rule.priority())
            .addAllDaysOfWeek(rule.daysOfWeek())
            .addAllChannels(rule.channels())
            .setActionType(rule.actionType())
            .setActionValue(rule.actionValue());
        if (rule.effectiveToExclusive() != null) {
            snapshot.setEffectiveToExclusive(rule.effectiveToExclusive());
        }
        if (rule.minimumStayDays() != null) snapshot.setMinimumStayDays(rule.minimumStayDays());
        if (rule.maximumStayDays() != null) snapshot.setMaximumStayDays(rule.maximumStayDays());
        if (rule.minimumGuests() != null) snapshot.setMinimumGuests(rule.minimumGuests());
        if (rule.maximumGuests() != null) snapshot.setMaximumGuests(rule.maximumGuests());
        return snapshot.build();
    }

    private static void fail(
        StreamObserver<ReconcilePricingPublicationsResponse> observer,
        Status status,
        String description
    ) {
        observer.onError(status.withDescription(description).asRuntimeException());
    }
}
