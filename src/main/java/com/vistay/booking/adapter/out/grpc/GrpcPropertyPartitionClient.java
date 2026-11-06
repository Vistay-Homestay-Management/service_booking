package com.vistay.booking.adapter.out.grpc;

import com.vistay.booking.application.PropertyPartitionClient;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordRequest;
import com.vistay.contracts.property.v1.GetPropertyPartitionRecordsRequest;
import com.vistay.contracts.property.v1.PropertyPartitionResolutionStatus;
import com.vistay.contracts.property.v1.PropertyPartitionServiceGrpc;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.util.UUID;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @Override
    public Map<UUID, Resolution> resolveAll(Collection<UUID> propertyIds) {
        var ids = propertyIds == null ? java.util.Set.<UUID>of()
            : new java.util.LinkedHashSet<>(propertyIds);
        if (ids.isEmpty()) return Map.of();
        try {
            var response = stub.withDeadlineAfter(deadline.toMillis(), TimeUnit.MILLISECONDS)
                .getPropertyPartitionRecords(GetPropertyPartitionRecordsRequest.newBuilder()
                    .addAllPropertyIds(ids.stream().map(UUID::toString).toList()).build());
            Map<UUID, Resolution> result = new LinkedHashMap<>();
            for (var record : response.getRecordsList()) {
                UUID propertyId;
                try { propertyId = UUID.fromString(record.getPropertyId()); }
                catch (IllegalArgumentException malformed) { continue; }
                if (!ids.contains(propertyId)) continue;
                if (record.getResolutionStatus()
                    == PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MISSING) {
                    result.put(propertyId, Resolution.unmapped());
                } else if (record.getResolutionStatus()
                    == PropertyPartitionResolutionStatus.PROPERTY_PARTITION_RESOLUTION_STATUS_MAPPED
                    && record.hasBranchId() && record.getMembershipVersion() >= 0) {
                    try {
                        result.put(propertyId, Resolution.mapped(UUID.fromString(record.getBranchId()),
                            record.getMembershipVersion()));
                    } catch (IllegalArgumentException malformed) {
                        result.put(propertyId, Resolution.unavailable());
                    }
                } else {
                    result.put(propertyId, Resolution.unavailable());
                }
            }
            response.getMissingPropertyIdsList().forEach(value -> {
                try { result.put(UUID.fromString(value), Resolution.notFound()); }
                catch (IllegalArgumentException ignored) { }
            });
            ids.forEach(id -> result.putIfAbsent(id, Resolution.unavailable()));
            return Map.copyOf(result);
        } catch (StatusRuntimeException failure) {
            Map<UUID, Resolution> unavailable = new LinkedHashMap<>();
            ids.forEach(id -> unavailable.put(id, Resolution.unavailable()));
            return Map.copyOf(unavailable);
        }
    }
}
