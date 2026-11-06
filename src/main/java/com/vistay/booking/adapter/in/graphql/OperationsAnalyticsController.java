package com.vistay.booking.adapter.in.graphql;

import graphql.GraphqlErrorException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/** A bounded, read-only projection for the Admin analytics workspace. */
@Controller
public class OperationsAnalyticsController {
    private static final String ANALYTICS_READ = "analytics:read";
    private final JdbcClient jdbc;
    private final AuthorizationContext authorization;

    public OperationsAnalyticsController(JdbcClient jdbc, AuthorizationContext authorization) {
        this.jdbc = jdbc;
        this.authorization = authorization;
    }

    @QueryMapping
    @RequiresPermission(ANALYTICS_READ)
    public OperationsAnalytics operationsAnalytics(@AuthenticationPrincipal Jwt jwt,
        @Argument AnalyticsRangeInput input) {
        var scopes = authorization.require(jwt, ANALYTICS_READ);
        if (!scopes.contains("ALL")) {
            throw error("FORBIDDEN_ANALYTICS_SCOPE", "Cross-property analytics requires ALL scope");
        }
        var range = validate(input);
        var summary = jdbc.sql("""
            SELECT COUNT(*) AS total_bookings,
              COUNT(*) FILTER (WHERE status IN ('CONFIRMED','CHECKED_IN','COMPLETED')) AS confirmed_bookings,
              COUNT(*) FILTER (WHERE status = 'CANCELLED') AS cancelled_bookings,
              COALESCE(SUM(adults + children), 0) AS guests,
              COUNT(DISTINCT property_id) AS active_properties,
              COALESCE(SUM(GREATEST(0, LEAST(check_out_date, :toDate) - GREATEST(check_in_date, :fromDate)))
                FILTER (WHERE status IN ('CONFIRMED','CHECKED_IN','COMPLETED')), 0) AS occupied_nights
            FROM bookings
            WHERE created_at >= :fromAt AND created_at < :toAt
            """).param("fromDate", range.from()).param("toDate", range.to())
            .param("fromAt", range.fromAt()).param("toAt", range.toAt())
            .query(SummaryRow.class).single();
        var money = jdbc.sql("""
            SELECT currency,
              COALESCE(SUM(total_amount) FILTER (WHERE status NOT IN ('CANCELLED','EXPIRED')), 0) AS revenue,
              COALESCE(AVG(total_amount) FILTER (WHERE status NOT IN ('CANCELLED','EXPIRED')), 0) AS average_booking_value
            FROM bookings WHERE created_at >= :fromAt AND created_at < :toAt
            GROUP BY currency ORDER BY currency
            """).param("fromAt", range.fromAt()).param("toAt", range.toAt())
            .query(MoneyRow.class).list();
        var series = jdbc.sql("""
            WITH days AS (
              SELECT generate_series(CAST(:fromDate AS date), (CAST(:toDate AS date) - 1), interval '1 day')::date AS day
            )
            SELECT days.day,
              COUNT(bookings.id) AS bookings,
              COALESCE(SUM(bookings.total_amount)
                FILTER (WHERE bookings.status NOT IN ('CANCELLED','EXPIRED')), 0) AS revenue,
              COALESCE(SUM(bookings.adults + bookings.children), 0) AS guests
            FROM days LEFT JOIN bookings ON bookings.created_at >= days.day
              AND bookings.created_at < days.day + interval '1 day'
            GROUP BY days.day ORDER BY days.day
            """).param("fromDate", range.from()).param("toDate", range.to())
            .query(SeriesRow.class).list();
        var channels = jdbc.sql("""
            SELECT source_channel AS code, COUNT(*) AS bookings,
              COALESCE(SUM(total_amount) FILTER (WHERE status NOT IN ('CANCELLED','EXPIRED')), 0) AS revenue
            FROM bookings WHERE created_at >= :fromAt AND created_at < :toAt
            GROUP BY source_channel ORDER BY bookings DESC, source_channel
            """).param("fromAt", range.fromAt()).param("toAt", range.toAt())
            .query(BreakdownRow.class).list();
        var statuses = jdbc.sql("""
            SELECT status AS code, COUNT(*) AS bookings, 0::numeric AS revenue
            FROM bookings WHERE created_at >= :fromAt AND created_at < :toAt
            GROUP BY status ORDER BY bookings DESC, status
            """).param("fromAt", range.fromAt()).param("toAt", range.toAt())
            .query(BreakdownRow.class).list();
        var days = Math.max(1, ChronoUnit.DAYS.between(range.from(), range.to()));
        var capacity = (long) summary.activeProperties() * days;
        var occupancy = capacity == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(summary.occupiedNights())
            .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(capacity), 2, java.math.RoundingMode.HALF_UP);
        var cancellation = summary.totalBookings() == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(summary.cancelledBookings()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(summary.totalBookings()), 2, java.math.RoundingMode.HALF_UP);
        return new OperationsAnalytics(range.from().toString(), range.to().toString(),
            OffsetDateTime.now(ZoneOffset.UTC).toString(), summary.totalBookings(),
            summary.confirmedBookings(), summary.cancelledBookings(), summary.guests(),
            summary.activeProperties(), summary.occupiedNights(), occupancy.toPlainString(),
            cancellation.toPlainString(), money, series, channels, statuses);
    }

    private static Range validate(AnalyticsRangeInput input) {
        if (input == null) throw error("INVALID_ANALYTICS_RANGE", "Analytics range is required");
        try {
            var from = LocalDate.parse(input.from());
            var to = LocalDate.parse(input.to());
            var days = ChronoUnit.DAYS.between(from, to);
            if (days < 1 || days > 366) throw new IllegalArgumentException();
            return new Range(from, to, from.atStartOfDay().atOffset(ZoneOffset.UTC),
                to.atStartOfDay().atOffset(ZoneOffset.UTC));
        } catch (RuntimeException invalid) {
            throw error("INVALID_ANALYTICS_RANGE", "Range must contain between 1 and 366 UTC days");
        }
    }

    private static GraphqlErrorException error(String code, String message) {
        return GraphqlErrorException.newErrorException().message(message)
            .errorClassification(code.startsWith("FORBIDDEN") ? ErrorType.FORBIDDEN : ErrorType.BAD_REQUEST)
            .extensions(Map.of("code", code)).build();
    }

    public record AnalyticsRangeInput(String from, String to) {}
    public record OperationsAnalytics(String from, String to, String generatedAt,
        long totalBookings, long confirmedBookings, long cancelledBookings, long guests,
        long activeProperties, long occupiedNights, String occupancyRate,
        String cancellationRate, List<MoneyRow> money, List<SeriesRow> series,
        List<BreakdownRow> channels, List<BreakdownRow> statuses) {}
    public record MoneyRow(String currency, BigDecimal revenue, BigDecimal averageBookingValue) {}
    public record SeriesRow(LocalDate day, long bookings, BigDecimal revenue, long guests) {}
    public record BreakdownRow(String code, long bookings, BigDecimal revenue) {}
    private record SummaryRow(long totalBookings, long confirmedBookings, long cancelledBookings,
        long guests, long activeProperties, long occupiedNights) {}
    private record Range(LocalDate from, LocalDate to, OffsetDateTime fromAt, OffsetDateTime toAt) {}
}
