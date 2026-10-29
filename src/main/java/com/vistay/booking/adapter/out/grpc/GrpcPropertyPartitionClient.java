package com.vistay.booking.adapter.out.grpc;

import com.vistay.booking.application.PropertyPartitionClient;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordRequest;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.contracts.property.v1.PropertyPartitionServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.stereotype.Component;

@Component
public class GrpcPropertyPartitionClient implements PropertyPartitionClient {
    private final PropertyPartitionServiceGrpc.PropertyPartitionServiceBlockingStub stub;
    private final Duration deadline;

    @Autowired
    public GrpcPropertyPartitionClient(
        GrpcChannelFactory channels,
        @Value("${vistay.property.partition-deadline:2s}") Duration deadline
    ) {
        this.stub = PropertyPartitionServiceGrpc.newBlockingStub(channels.createChannel("property"));
        this.deadline = deadline;
    }

    GrpcPropertyPartitionClient(
        PropertyPartitionServiceGrpc.PropertyPartitionServiceBlockingStub stub,
        Duration deadline
    ) {
        this.stub = stub;
        this.deadline = deadline;
    }

    @Override
    public Resolution resolve(UUID propertyId) {
        try {
            var requestedPropertyId = propertyId.toString();
            var response = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getPropertyPartitionRecord(GetPropertyPartitionRecordRequest.newBuilder()
                    .setPropertyId(requestedPropertyId).build());
            try {
                if (!propertyId.equals(UUID.fromString(response.getPropertyId()))) {
                    return Resolution.unavailable();
                }
            } catch (IllegalArgumentException malformedPropertyIdentity) {
                return Resolution.unavailable();
            }
            if (response.getResolutionStatus()
                == PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING) {
                return Resolution.unmapped();
            }
            if (response.getResolutionStatus()
                != PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED
                || !response.hasBranchId() || response.getMembershipVersion() < 0) {
                return Resolution.unavailable();
            }
            try {
                return Resolution.mapped(UUID.fromString(response.getBranchId()),
                    response.getMembershipVersion());
            } catch (IllegalArgumentException malformedContract) {
                return Resolution.unavailable();
            }
        } catch (StatusRuntimeException failure) {
            if (failure.getStatus().getCode() == io.grpc.Status.Code.NOT_FOUND) {
                return Resolution.notFound();
            }
            return Resolution.unavailable();
        }
    }
}
