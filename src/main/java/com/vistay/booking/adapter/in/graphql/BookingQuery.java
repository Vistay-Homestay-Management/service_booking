package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Scope;
import graphql.GraphqlErrorException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class BookingQuery {
    private final JdbcClient jdbc;
    private final AuthorizationClient authorizations;

    public BookingQuery(JdbcClient jdbc, AuthorizationClient authorizations) {
        this.jdbc = jdbc;
        this.authorizations = authorizations;
    }

    @QueryMapping
    public BookingView booking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, "booking:read");
        var row = jdbc.sql("""
                SELECT id, booking_code, property_id, status, check_in_date, check_out_date,
                       total_amount, currency, created_by_account_type, created_by_account_id,
                       assigned_admin_user_id
                FROM bookings WHERE id = :id
                """).param("id", id).query(BookingRow.class).optional()
            .orElseThrow(() -> error("NOT_FOUND", "Booking was not found"));
        if (!allowed(row, accountId, snapshot)) {
            auditDenied(accountId, row.id());
            throw error("FORBIDDEN_SCOPE", "Booking is outside the authorized data scope");
        }
        return row.view();
    }

    @QueryMapping
    public List<BookingView> bookings(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String status,
        @Argument Integer limit
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, "booking:read");
        var scopes = snapshot.scopes("booking:read");
        var pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        var normalizedStatus = status == null ? "" : status.trim();
        return jdbc.sql("""
                SELECT b.id, b.booking_code, b.property_id, b.status, b.check_in_date, b.check_out_date,
                       b.total_amount, b.currency, b.created_by_account_type, b.created_by_account_id,
                       b.assigned_admin_user_id
                FROM bookings b
                WHERE (NOT :hasStatus OR b.status = :status)
                  AND (
                    :allScope
                    OR (:ownScope AND b.created_by_account_type = 'ADMIN' AND b.created_by_account_id = :accountId)
                    OR (:assignedScope AND (
                        b.assigned_admin_user_id = :profileId
                        OR EXISTS (
                            SELECT 1 FROM booking_attributions ba
                            WHERE ba.booking_id = b.id AND ba.admin_user_id = :profileId
                              AND ba.valid_from <= now()
                              AND (ba.valid_until IS NULL OR ba.valid_until > now())
                        )
                    ))
                  )
                ORDER BY b.created_at DESC, b.id
                LIMIT :limit
                """)
            .param("hasStatus", !normalizedStatus.isBlank())
            .param("status", normalizedStatus)
            .param("allScope", scopes.contains(Scope.ALL))
            .param("ownScope", scopes.contains(Scope.OWN))
            .param("assignedScope", scopes.contains(Scope.ASSIGNED))
            .param("accountId", accountId)
            .param("profileId", snapshot.profileId())
            .param("limit", pageSize)
            .query(BookingRow.class)
            .list().stream().map(BookingRow::view).toList();
    }

    private boolean allowed(BookingRow booking, UUID accountId, AuthorizationClient.Snapshot snapshot) {
        var scopes = snapshot.scopes("booking:read");
        if (scopes.contains(Scope.ALL)) return true;
        if (scopes.contains(Scope.OWN)
            && "ADMIN".equals(booking.createdByAccountType())
            && accountId.equals(booking.createdByAccountId())) return true;
        if (!scopes.contains(Scope.ASSIGNED)) return false;
        if (snapshot.profileId().equals(booking.assignedAdminUserId())) return true;
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM booking_attributions
                    WHERE booking_id = :bookingId AND admin_user_id = :profileId
                      AND valid_from <= now() AND (valid_until IS NULL OR valid_until > now())
                )
                """).param("bookingId", booking.id()).param("profileId", snapshot.profileId())
            .query(Boolean.class).single();
    }

    private void auditDenied(UUID actorAccountId, UUID bookingId) {
        jdbc.sql("""
                INSERT INTO audit_events(actor_account_type, actor_account_id, action,
                    aggregate_type, aggregate_id, changes)
                VALUES ('ADMIN', :actorId, 'AUTHORIZATION_DENIED', 'Booking', :bookingId,
                    '{"reason":"FORBIDDEN_SCOPE","permission":"booking:read"}'::jsonb)
                """).param("actorId", actorAccountId).param("bookingId", bookingId).update();
    }

    private static UUID accountId(Jwt jwt) {
        if (jwt == null) throw error("UNAUTHENTICATED", "Authentication is required");
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException invalid) { throw error("INVALID_TOKEN", "Token subject is invalid"); }
    }

    private static GraphqlErrorException error(String code, String message) {
        var classification = code.equals("FORBIDDEN_SCOPE") ? ErrorType.FORBIDDEN
            : code.endsWith("NOT_FOUND") ? ErrorType.NOT_FOUND : ErrorType.BAD_REQUEST;
        return GraphqlErrorException.newErrorException().message(message)
            .errorClassification(classification).extensions(Map.of("code", code)).build();
    }

    private record BookingRow(UUID id, String bookingCode, UUID propertyId, String status,
        LocalDate checkInDate, LocalDate checkOutDate, BigDecimal totalAmount, String currency,
        String createdByAccountType, UUID createdByAccountId, UUID assignedAdminUserId) {
        BookingView view() {
            return new BookingView(id, bookingCode, propertyId, status, checkInDate.toString(),
                checkOutDate.toString(), totalAmount.toPlainString(), currency.trim());
        }
    }

    public record BookingView(UUID id, String bookingCode, UUID propertyId, String status,
        String checkInDate, String checkOutDate, String totalAmount, String currency) {}
}
