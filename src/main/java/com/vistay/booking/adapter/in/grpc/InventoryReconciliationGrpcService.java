package com.vistay.booking.adapter.in.grpc;

import com.vistay.booking.application.InventoryReconciliationService;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.config.GrpcTrustBoundaryInterceptor;
import com.vistay.contracts.booking.v1.InventoryReconciliationBlock;
import com.vistay.contracts.booking.v1.InventoryReconciliationServiceGrpc;
import com.vistay.contracts.booking.v1.ReconcileInventoryRequest;
import com.vistay.contracts.booking.v1.ReconcileInventoryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService(interceptors = GrpcTrustBoundaryInterceptor.class)
public class InventoryReconciliationGrpcService
    extends InventoryReconciliationServiceGrpc.InventoryReconciliationServiceImplBase {

    private final InventoryReconciliationService inventory;
    private final Set<String> allowedCallerUris;

    public InventoryReconciliationGrpcService(
        InventoryReconciliationService inventory,
        @Value("${vistay.grpc.inventory-reconciliation-allowed-caller-uris:"
            + "spiffe://vistay/service/property}") List<String> allowedCallerUris
    ) {
        this.inventory = inventory;
        this.allowedCallerUris = Set.copyOf(allowedCallerUris);
    }

    @Override
    public void reconcileInventory(
        ReconcileInventoryRequest request,
        StreamObserver<ReconcileInventoryResponse> observer
    ) {
        var callerUri = GrpcTrustBoundaryInterceptor.AUTHENTICATED_CALLER_URI.get();
        if (callerUri == null) {
            fail(observer, Status.UNAUTHENTICATED,
                "Authenticated mTLS caller identity is required");
            return;
        }
        if (!allowedCallerUris.contains(callerUri)) {
            fail(observer, Status.PERMISSION_DENIED,
                "Caller is not allowed to reconcile inventory");
            return;
        }

        final UUID propertyId;
        try {
            var value = request.getPropertyId();
            if (!value.equals(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            propertyId = UUID.fromString(value);
            if (!propertyId.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
        } catch (IllegalArgumentException invalid) {
            fail(observer, Status.INVALID_ARGUMENT,
                "property_id must be a canonical lowercase UUID");
            return;
        }
        if (request.getConsumerInventoryRevision() < 0) {
            fail(observer, Status.INVALID_ARGUMENT,
                "consumer_inventory_revision must not be negative");
            return;
        }

        try {
            var snapshot = inventory.reconcile(
                propertyId, request.getConsumerInventoryRevision());
            var response = ReconcileInventoryResponse.newBuilder()
                .setPropertyId(snapshot.propertyId().toString())
                .setInventoryRevision(snapshot.inventoryRevision())
                .setNotModified(snapshot.notModified())
                .setObservedAt(snapshot.observedAt().toString());
            snapshot.blocks().stream().map(InventoryReconciliationGrpcService::block)
                .forEach(response::addBlocks);
            observer.onNext(response.build());
            observer.onCompleted();
        } catch (PricingPolicyException invalidState) {
            var status = "INVENTORY_RECONCILIATION_AHEAD".equals(invalidState.code())
                ? Status.FAILED_PRECONDITION : Status.INTERNAL;
            fail(observer, status, invalidState.getMessage());
        } catch (DataAccessException unavailable) {
            fail(observer, Status.UNAVAILABLE,
                "Inventory reconciliation store is unavailable");
        }
    }

    private static InventoryReconciliationBlock block(
        InventoryReconciliationService.Block source
    ) {
        var result = InventoryReconciliationBlock.newBuilder()
            .setInventoryBlockId(source.id().toString())
            .setBlockType(source.type())
            .setStatus(source.status())
            .setStartDate(source.startDate().toString())
            .setEndDateExclusive(source.endDateExclusive().toString())
            .setBlockVersion(source.version());
        if (source.channelProvider() != null) {
            result.setChannelProvider(source.channelProvider());
        }
        return result.build();
    }

    private static void fail(
        StreamObserver<ReconcileInventoryResponse> observer,
        Status status,
        String description
    ) {
        observer.onError(status.withDescription(description).asRuntimeException());
    }
}
