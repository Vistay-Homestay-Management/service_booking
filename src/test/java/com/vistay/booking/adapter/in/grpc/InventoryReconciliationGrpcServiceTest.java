package com.vistay.booking.adapter.in.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vistay.booking.application.InventoryReconciliationService;
import com.vistay.booking.application.InventoryReconciliationService.Block;
import com.vistay.booking.application.InventoryReconciliationService.Snapshot;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.config.GrpcTrustBoundaryInterceptor;
import com.vistay.contracts.booking.v1.ReconcileInventoryRequest;
import com.vistay.contracts.booking.v1.ReconcileInventoryResponse;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryReconciliationGrpcServiceTest {
    private static final String PROPERTY_CALLER = "spiffe://vistay/service/property";
    private static final UUID PROPERTY_ID =
        UUID.fromString("2abcdef0-0000-4000-8000-000000000003");

    @Test
    void returnsPrivacyMinimizedRevisionPinnedSnapshotForAllowedCaller() {
        var inventory = mock(InventoryReconciliationService.class);
        var blockId = UUID.randomUUID();
        when(inventory.reconcile(PROPERTY_ID, 4)).thenReturn(new Snapshot(
            PROPERTY_ID, 6, false, List.of(new Block(blockId, "CHANNEL", "ACTIVE",
                LocalDate.parse("2027-02-01"), LocalDate.parse("2027-02-03"),
                2, "CHANNEX")), OffsetDateTime.parse("2026-10-30T04:00:00Z")));
        var service = new InventoryReconciliationGrpcService(
            inventory, List.of(PROPERTY_CALLER));
        var observer = new CapturingObserver<ReconcileInventoryResponse>();

        asCaller(PROPERTY_CALLER, () -> service.reconcileInventory(
            ReconcileInventoryRequest.newBuilder()
                .setPropertyId(PROPERTY_ID.toString())
                .setConsumerInventoryRevision(4)
                .build(), observer));

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getPropertyId()).isEqualTo(PROPERTY_ID.toString());
        assertThat(observer.value.getInventoryRevision()).isEqualTo(6);
        assertThat(observer.value.getNotModified()).isFalse();
        assertThat(observer.value.getObservedAt()).isEqualTo("2026-10-30T04:00Z");
        assertThat(observer.value.getBlocksCount()).isEqualTo(1);
        var block = observer.value.getBlocks(0);
        assertThat(block.getInventoryBlockId()).isEqualTo(blockId.toString());
        assertThat(block.getChannelProvider()).isEqualTo("CHANNEX");
        assertThat(block.getBlockVersion()).isEqualTo(2);
        verify(inventory).reconcile(PROPERTY_ID, 4);
    }

    @Test
    void deniesMissingOrForeignCallerBeforeReadingInventory() {
        var inventory = mock(InventoryReconciliationService.class);
        var service = new InventoryReconciliationGrpcService(
            inventory, List.of(PROPERTY_CALLER));
        var request = request(PROPERTY_ID.toString(), 0);

        var missing = new CapturingObserver<ReconcileInventoryResponse>();
        service.reconcileInventory(request, missing);
        assertThat(status(missing)).isEqualTo(Status.Code.UNAUTHENTICATED);

        var denied = new CapturingObserver<ReconcileInventoryResponse>();
        asCaller("spiffe://vistay/service/payment",
            () -> service.reconcileInventory(request, denied));
        assertThat(status(denied)).isEqualTo(Status.Code.PERMISSION_DENIED);
        verifyNoInteractions(inventory);
    }

    @Test
    void rejectsMalformedInputAndMapsConsumerAheadWithoutLeakingStorage() {
        var inventory = mock(InventoryReconciliationService.class);
        var service = new InventoryReconciliationGrpcService(
            inventory, List.of(PROPERTY_CALLER));
        for (var request : List.of(
            request("NOT-A-UUID", 0),
            request(PROPERTY_ID.toString().toUpperCase(), 0),
            request(PROPERTY_ID.toString(), -1))) {
            var observer = new CapturingObserver<ReconcileInventoryResponse>();
            asCaller(PROPERTY_CALLER, () -> service.reconcileInventory(request, observer));
            assertThat(status(observer)).isEqualTo(Status.Code.INVALID_ARGUMENT);
        }

        when(inventory.reconcile(PROPERTY_ID, 9)).thenThrow(
            new PricingPolicyException("INVENTORY_RECONCILIATION_AHEAD", "ahead"));
        var ahead = new CapturingObserver<ReconcileInventoryResponse>();
        asCaller(PROPERTY_CALLER, () -> service.reconcileInventory(
            request(PROPERTY_ID.toString(), 9), ahead));
        assertThat(status(ahead)).isEqualTo(Status.Code.FAILED_PRECONDITION);
    }

    private static ReconcileInventoryRequest request(String propertyId, long revision) {
        return ReconcileInventoryRequest.newBuilder()
            .setPropertyId(propertyId)
            .setConsumerInventoryRevision(revision)
            .build();
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
