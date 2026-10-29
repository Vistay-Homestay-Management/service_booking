package com.vistay.booking.adapter.in.grpc;

import com.vistay.booking.application.BookingAuthorizationRecordReader;
import com.vistay.booking.application.BookingAuthorizationRecordReader.Record;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordRequest;
import com.vistay.contracts.booking.v1.GetBookingAuthorizationRecordResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookingAuthorizationGrpcServiceTest {

    @Test
    void returnsOnlyAuthoritativeActiveRelationships() {
        var bookingId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var propertyId = UUID.randomUUID();
        var assignedId = UUID.randomUUID();
        var attributedId = UUID.randomUUID();
        var records = mock(BookingAuthorizationRecordReader.class);
        when(records.find(bookingId)).thenReturn(Optional.of(new Record(
            bookingId, customerId, propertyId, assignedId, List.of(attributedId))));
        var observer = new CapturingObserver<GetBookingAuthorizationRecordResponse>();

        new BookingAuthorizationGrpcService(records).getBookingAuthorizationRecord(
            GetBookingAuthorizationRecordRequest.newBuilder()
                .setBookingId(bookingId.toString()).build(), observer);

        assertThat(observer.error).isNull();
        assertThat(observer.completed).isTrue();
        assertThat(observer.value.getCustomerId()).isEqualTo(customerId.toString());
        assertThat(observer.value.getPropertyId()).isEqualTo(propertyId.toString());
        assertThat(observer.value.getAssignedAdminUserId()).isEqualTo(assignedId.toString());
        assertThat(observer.value.getActiveAttributedAdminUserIdsList())
            .containsExactly(attributedId.toString());
    }

    @Test
    void returnsNotFoundWithoutLeakingARecord() {
        var bookingId = UUID.randomUUID();
        var records = mock(BookingAuthorizationRecordReader.class);
        when(records.find(bookingId)).thenReturn(Optional.empty());
        var observer = new CapturingObserver<GetBookingAuthorizationRecordResponse>();

        new BookingAuthorizationGrpcService(records).getBookingAuthorizationRecord(
            GetBookingAuthorizationRecordRequest.newBuilder()
                .setBookingId(bookingId.toString()).build(), observer);

        assertThat(((StatusRuntimeException) observer.error).getStatus().getCode())
            .isEqualTo(Status.Code.NOT_FOUND);
        assertThat(observer.value).isNull();
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
