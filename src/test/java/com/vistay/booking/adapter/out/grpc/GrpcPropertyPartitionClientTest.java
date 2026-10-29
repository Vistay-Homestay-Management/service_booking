package com.vistay.booking.adapter.out.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vistay.booking.application.PropertyPartitionClient.Status;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordResponse;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.contracts.property.v1.PropertyPartitionServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GrpcPropertyPartitionClientTest {
    private PropertyPartitionServiceGrpc.PropertyPartitionServiceBlockingStub stub;
    private GrpcPropertyPartitionClient client;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stub = mock(PropertyPartitionServiceGrpc.PropertyPartitionServiceBlockingStub.class);
        when(stub.withDeadlineAfter(any(Long.class), any(TimeUnit.class))).thenReturn(stub);
        client = new GrpcPropertyPartitionClient(stub, Duration.ofSeconds(2));
    }

    @Test
    void responseMissingMeansKnownContractButUnmappedProperty() {
        var propertyId = UUID.randomUUID();
        when(stub.getPropertyPartitionRecord(any())).thenReturn(
            GetPropertyPartitionRecordResponse.newBuilder()
                .setPropertyId(propertyId.toString())
                .setResolutionStatus(PropertyPartitionResolutionStatus
                    .PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING)
                .build());

        assertThat(client.resolve(propertyId).status()).isEqualTo(Status.UNMAPPED);
    }

    @Test
    void mappedResponseRetainsAuthoritativeBranchAndMembershipVersion() {
        var propertyId = UUID.randomUUID();
        var branchId = UUID.randomUUID();
        when(stub.getPropertyPartitionRecord(any())).thenReturn(
            GetPropertyPartitionRecordResponse.newBuilder()
                .setPropertyId(propertyId.toString())
                .setResolutionStatus(PropertyPartitionResolutionStatus
                    .PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
                .setBranchId(branchId.toString())
                .setMembershipVersion(29)
                .build());

        var result = client.resolve(propertyId);
        assertThat(result.status()).isEqualTo(Status.MAPPED);
        assertThat(result.branchId()).isEqualTo(branchId);
        assertThat(result.membershipVersion()).isEqualTo(29);
    }

    @Test
    void grpcNotFoundRemainsDistinctFromUnmapped() {
        when(stub.getPropertyPartitionRecord(any())).thenThrow(
            new StatusRuntimeException(io.grpc.Status.NOT_FOUND));

        assertThat(client.resolve(UUID.randomUUID()).status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void unavailableResponseFailsClosed() {
        when(stub.getPropertyPartitionRecord(any())).thenThrow(
            new StatusRuntimeException(io.grpc.Status.DEADLINE_EXCEEDED));
        assertThat(client.resolve(UUID.randomUUID()).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void malformedMappedResponseFailsClosed() {
        var propertyId = UUID.randomUUID();
        when(stub.getPropertyPartitionRecord(any())).thenReturn(
            GetPropertyPartitionRecordResponse.newBuilder()
                .setPropertyId(propertyId.toString())
                .setResolutionStatus(PropertyPartitionResolutionStatus
                    .PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
                .setBranchId("not-a-uuid")
                .build());
        assertThat(client.resolve(propertyId).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void negativeMembershipVersionFailsClosed() {
        var propertyId = UUID.randomUUID();
        when(stub.getPropertyPartitionRecord(any())).thenReturn(
            GetPropertyPartitionRecordResponse.newBuilder()
                .setPropertyId(propertyId.toString())
                .setResolutionStatus(PropertyPartitionResolutionStatus
                    .PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED)
                .setBranchId(UUID.randomUUID().toString())
                .setMembershipVersion(-1)
                .build());

        assertThat(client.resolve(propertyId).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void mismatchedPropertyIdentityFailsClosed() {
        when(stub.getPropertyPartitionRecord(any())).thenReturn(
            GetPropertyPartitionRecordResponse.newBuilder()
                .setPropertyId(UUID.randomUUID().toString())
                .setResolutionStatus(PropertyPartitionResolutionStatus
                    .PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING)
                .build());

        assertThat(client.resolve(UUID.randomUUID()).status()).isEqualTo(Status.UNAVAILABLE);
    }

    @Test
    void malformedPropertyIdentityFailsClosed() {
        when(stub.getPropertyPartitionRecord(any())).thenReturn(
            GetPropertyPartitionRecordResponse.newBuilder()
                .setPropertyId("not-a-uuid")
                .setResolutionStatus(PropertyPartitionResolutionStatus
                    .PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING)
                .build());

        assertThat(client.resolve(UUID.randomUUID()).status()).isEqualTo(Status.UNAVAILABLE);
    }
}
