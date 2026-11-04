package com.vistay.booking.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingAuthorizationRecordReader {
    Optional<Record> find(UUID bookingId);

    record Record(
        UUID bookingId,
        UUID customerId,
        UUID propertyId,
        UUID assignedAdminUserId,
        List<UUID> activeAttributedAdminUserIds
    ) {}
}
