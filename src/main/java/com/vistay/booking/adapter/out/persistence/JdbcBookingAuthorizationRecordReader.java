    package com.vistay.booking.adapter.out.persistence;

    import com.vistay.booking.application.BookingAuthorizationRecordReader;
    import java.sql.SQLException;
    import java.util.Arrays;
    import java.util.Collection;
    import java.util.List;
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
            return findAll(List.of(bookingId)).stream().findFirst();
        }

        @Override
        public List<Record> findAll(Collection<UUID> bookingIds) {
            if (bookingIds == null || bookingIds.isEmpty()) return List.of();
            return jdbc.sql("""
                    SELECT b.id AS booking_id, b.customer_id, b.property_id,
                           b.assigned_admin_user_id,
                           COALESCE(
                               array_agg(ba.admin_user_id ORDER BY ba.admin_user_id)
                                   FILTER (WHERE ba.admin_user_id IS NOT NULL),
                               ARRAY[]::uuid[]
                           ) AS active_attributed_admin_user_ids
                    FROM bookings b
                    LEFT JOIN booking_attributions ba
                      ON ba.booking_id = b.id
                     AND ba.valid_from <= now()
                     AND (ba.valid_until IS NULL OR ba.valid_until > now())
                    WHERE b.id IN (:bookingIds)
                    GROUP BY b.id, b.customer_id, b.property_id, b.assigned_admin_user_id
                    ORDER BY b.id
                    """)
                .param("bookingIds", bookingIds)
                .query((row, rowNumber) -> {
                    try {
                        var raw = (UUID[]) row.getArray("active_attributed_admin_user_ids").getArray();
                        return new Record(
                            row.getObject("booking_id", UUID.class),
                            row.getObject("customer_id", UUID.class),
                            row.getObject("property_id", UUID.class),
                            row.getObject("assigned_admin_user_id", UUID.class),
                            List.copyOf(Arrays.asList(raw))
                        );
                    } catch (SQLException invalidArray) {
                        throw new IllegalStateException("Cannot decode booking attribution aggregate", invalidArray);
                    }
                })
                .list();
        }
    }
