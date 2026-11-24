package com.vistay.booking.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Low-cardinality operational booking metrics. Values are refreshed from the
 * authoritative booking store so Grafana remains useful after restarts.
 */
@Component
public class BookingBusinessMetrics {
    private static final String[] STATUSES = {
        "PENDING_CONFIRMATION", "HELD", "CONFIRMED", "CHECKED_IN",
        "COMPLETED", "CANCELLED", "NO_SHOW", "EXPIRED"
    };

    private final JdbcClient jdbc;
    private final Map<String, AtomicLong> bookingsByStatus = new LinkedHashMap<>();
    private final AtomicLong arrivalsToday = new AtomicLong();
    private final AtomicLong departuresToday = new AtomicLong();
    private final AtomicLong guestsInHouse = new AtomicLong();
    private final AtomicLong roomsOccupiedToday = new AtomicLong();
    private final AtomicLong confirmedRevenueVnd = new AtomicLong();
    private final Map<String, Counter> commands = new LinkedHashMap<>();

    public BookingBusinessMetrics(JdbcClient jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (var status : STATUSES) {
            var value = new AtomicLong();
            bookingsByStatus.put(status, value);
            Gauge.builder("vistay_booking_records", value, AtomicLong::get)
                .description("Current booking records by lifecycle status")
                .tag("status", status)
                .register(registry);
        }
        Gauge.builder("vistay_booking_arrivals", arrivalsToday, AtomicLong::get)
            .description("Bookings scheduled to check in today").tag("window", "today").register(registry);
        Gauge.builder("vistay_booking_departures", departuresToday, AtomicLong::get)
            .description("Bookings scheduled to check out today").tag("window", "today").register(registry);
        Gauge.builder("vistay_booking_guests_in_house", guestsInHouse, AtomicLong::get)
            .description("Guests staying in checked-in bookings today").register(registry);
        Gauge.builder("vistay_booking_rooms_occupied", roomsOccupiedToday, AtomicLong::get)
            .description("Distinct units occupied by checked-in bookings today").register(registry);
        Gauge.builder("vistay_booking_confirmed_value", confirmedRevenueVnd, AtomicLong::get)
            .description("Value of confirmed, checked-in, and completed VND bookings")
            .tag("currency", "VND").register(registry);
        for (var operation : new String[] {"create", "confirm", "check_in", "check_out", "cancel"}) {
            commands.put(operation, Counter.builder("vistay_booking_commands")
                .description("Successful booking lifecycle commands")
                .tag("operation", operation)
                .register(registry));
        }
    }

    @PostConstruct
    @Scheduled(fixedDelayString = "${vistay.observability.business-metrics-refresh:60s}")
    public void refresh() {
        try {
            for (var entry : bookingsByStatus.entrySet()) {
                entry.getValue().set(count("SELECT count(*) FROM bookings WHERE status = :status", entry.getKey()));
            }
            arrivalsToday.set(count("SELECT count(*) FROM bookings WHERE check_in_date = current_date AND status IN ('CONFIRMED','CHECKED_IN')"));
            departuresToday.set(count("SELECT count(*) FROM bookings WHERE check_out_date = current_date AND status IN ('CONFIRMED','CHECKED_IN')"));
            guestsInHouse.set(count("SELECT COALESCE(sum(adults + children), 0) FROM bookings WHERE status = 'CHECKED_IN' AND check_in_date <= current_date AND check_out_date > current_date"));
            roomsOccupiedToday.set(count("SELECT count(DISTINCT property_id) FROM bookings WHERE status = 'CHECKED_IN' AND check_in_date <= current_date AND check_out_date > current_date"));
            confirmedRevenueVnd.set(count("SELECT COALESCE(sum(total_amount), 0)::bigint FROM bookings WHERE currency = 'VND' AND status IN ('CONFIRMED','CHECKED_IN','COMPLETED')"));
        } catch (RuntimeException ignored) {
            // Metrics must remain fail-open: a transient database issue must not
            // affect booking commands or replace the last known values.
        }
    }

    public void commandSucceeded(String operation) {
        var counter = commands.get(operation);
        if (counter == null) return;
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            counter.increment();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                counter.increment();
            }
        });
    }

    private long count(String query, Object... values) {
        var statement = jdbc.sql(query);
        if (values.length == 1) statement = statement.param("status", values[0]);
        return statement.query((rs, rowNum) -> rs.getLong(1)).single();
    }
}
