package com.vistay.booking.adapter.in.grpc;

import com.vistay.booking.application.BookingAuthorizationRecordReader;
import com.vistay.booking.application.PropertyPartitionClient;
import com.vistay.booking.config.GrpcTrustBoundaryInterceptor;
import com.vistay.contracts.booking.v1.BookingAuthorizationServiceGrpc;
import com.vistay.contracts.booking.v1.BookingPartitionResolutionStatus;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordRequest;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.UUID;
import org.springframework.grpc.server.service.GrpcService;

@GrpcService(interceptors = GrpcTrustBoundaryInterceptor.class)
public class BookingAuthorizationGrpcService
    extends BookingAuthorizationServiceGrpc.BookingAuthorizationServiceImplBase {

    private final BookingAuthorizationRecordReader records;
    private final PropertyPartitionClient propertyPartitions;

    public BookingAuthorizationGrpcService(
        BookingAuthorizationRecordReader records,
        PropertyPartitionClient propertyPartitions
    ) {
        this.records = records;
        this.propertyPartitions = propertyPartitions;
    }

    @Override
    public void getBookingAuthorizationRecord(
        GetBookingAuthorizationRecordRequest request,
        StreamObserver<GetBookingAuthorizationRecordResponse> responseObserver
    ) {
        final UUID bookingId;
        try {
            bookingId = UUID.fromString(request.getBookingId());
        } catch (IllegalArgumentException invalid) {
            responseObserver.onError(Status.INVALID_ARGUMENT
                .withDescription("booking_id must be a UUID")
                .asRuntimeException());
            return;
        }

        var record = records.find(bookingId);
        if (record.isEmpty()) {
            responseObserver.onError(Status.NOT_FOUND
                .withDescription("Booking was not found")
                .asRuntimeException());
            return;
        }

        var source = record.get();
        var response = GetBookingAuthorizationRecordResponse.newBuilder()
            .setBookingId(source.bookingId().toString())
            .setPropertyId(source.propertyId().toString());
        if (source.customerId() != null) {
            response.setCustomerId(source.customerId().toString());
        }
        if (source.assignedAdminUserId() != null) {
            response.setAssignedAdminUserId(source.assignedAdminUserId().toString());
        }
        source.activeAttributedAdminUserIds().stream()
            .map(UUID::toString)
            .forEach(response::addActiveAttributedAdminUserIds);

        var partition = propertyPartitions.resolve(source.propertyId());
        switch (partition.status()) {
            case MAPPED -> response
                .setBranchId(partition.branchId().toString())
                .setPartitionResolutionStatus(
                    BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_MAPPED)
                .setPartitionMembershipVersion(partition.membershipVersion());
            case NOT_FOUND, UNMAPPED -> response.setPartitionResolutionStatus(
                BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_MISSING);
            case UNAVAILABLE -> response.setPartitionResolutionStatus(
                BookingPartitionResolutionStatus.BOOKING_PARTITION_RESOLUTION_STATUS_UNAVAILABLE);
        }

        responseObserver.onNext(response.build());
        responseObserver.onCompleted();
    }
}
