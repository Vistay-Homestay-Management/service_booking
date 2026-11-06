package com.vistay.booking.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingAuthorizationRecordReader {
    Optional<Record> find(UUID bookingId);

    default List<Record> findAll(Collection<UUID> bookingIds) {
        return bookingIds.stream().map(this::find).flatMap(Optional::stream).toList();
    }

    record Record(
        UUID bookingId,
        UUID customerId,
        UUID propertyId,
        UUID assignedAdminUserId,
        List<UUID> activeAttributedAdminUserIds
    ) {}
}
