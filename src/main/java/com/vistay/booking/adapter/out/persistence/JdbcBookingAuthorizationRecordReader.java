package com.vistay.booking.adapter.out.persistence;

import com.vistay.booking.application.BookingAuthorizationRecordReader;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class JdbcBookingAuthorizationRecordReader implements BookingAuthorizationRecordReader {
    private final JdbcClient jdbc;

    public JdbcBookingAuthorizationRecordReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public java.util.Optional<Record> find(UUID bookingId) {
        return jdbc.sql("""
                SELECT id AS booking_id, customer_id, property_id, assigned_admin_user_id
                FROM bookings
                WHERE id = :bookingId
                """)
            .param("bookingId", bookingId)
            .query(BaseRecord.class)
            .optional()
            .map(base -> new Record(
                base.bookingId(),
                base.customerId(),
                base.propertyId(),
                base.assignedAdminUserId(),
                jdbc.sql("""
                        SELECT admin_user_id
                        FROM booking_attributions
                        WHERE booking_id = :bookingId
                          AND valid_from <= now()
                          AND (valid_until IS NULL OR valid_until > now())
                        ORDER BY admin_user_id
                        """)
                    .param("bookingId", bookingId)
                    .query((row, rowNumber) -> row.getObject("admin_user_id", UUID.class))
                    .list()
            ));
    }

    private record BaseRecord(
        UUID bookingId,
        UUID customerId,
        UUID propertyId,
        UUID assignedAdminUserId
    ) {}
}
