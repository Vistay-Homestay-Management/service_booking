package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.BookingRecordScopePolicy;
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
    private final BookingRecordScopePolicy scopePolicy;

    public BookingQuery(
        JdbcClient jdbc,
        AuthorizationClient authorizations,
        BookingRecordScopePolicy scopePolicy
    ) {
        this.jdbc = jdbc;
        this.authorizations = authorizations;
        this.scopePolicy = scopePolicy;
    }

    @QueryMapping
    public BookingView booking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, "booking:read");
        var commercialScopes = snapshot.scopes("booking:read_sensitive");
        var row = jdbc.sql("""
                SELECT id, booking_code, property_id, status, check_in_date, check_out_date,
                       CASE WHEN :commercialAll OR (:commercialAssigned AND (
                           assigned_admin_user_id = :profileId
                           OR EXISTS (
                               SELECT 1 FROM booking_attributions ba
                               WHERE ba.booking_id = bookings.id AND ba.admin_user_id = :profileId
                                 AND ba.valid_from <= now()
                                 AND (ba.valid_until IS NULL OR ba.valid_until > now())
                           )
                       )) THEN total_amount ELSE NULL END AS total_amount,
                       currency, assigned_admin_user_id,
                       EXISTS (
                           SELECT 1 FROM booking_attributions ba
                           WHERE ba.booking_id = bookings.id AND ba.admin_user_id = :profileId
                             AND ba.valid_from <= now()
                             AND (ba.valid_until IS NULL OR ba.valid_until > now())
                       ) AS attributed_to_actor
                FROM bookings WHERE id = :id
                """)
            .param("id", id)
            .param("profileId", snapshot.profileId())
            .param("commercialAll", commercialScopes.contains(Scope.ALL))
            .param("commercialAssigned", commercialScopes.contains(Scope.ASSIGNED))
            .query(BookingRow.class).optional()
            .orElseThrow(() -> error("NOT_FOUND", "Booking was not found"));
        if (!scopePolicy.allows(snapshot.scopes("booking:read"), snapshot.profileId(),
            row.assignedAdminUserId(), row.attributedToActor())) {
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
        var commercialScopes = snapshot.scopes("booking:read_sensitive");
        var pageSize = limit == null ? 50 : Math.max(1, Math.min(limit, 100));
        var normalizedStatus = status == null ? "" : status.trim();
        return jdbc.sql("""
                SELECT b.id, b.booking_code, b.property_id, b.status, b.check_in_date, b.check_out_date,
                       CASE WHEN :commercialAll OR (:commercialAssigned AND (
                           b.assigned_admin_user_id = :profileId
                           OR EXISTS (
                               SELECT 1 FROM booking_attributions commercial_ba
                               WHERE commercial_ba.booking_id = b.id
                                 AND commercial_ba.admin_user_id = :profileId
                                 AND commercial_ba.valid_from <= now()
                                 AND (commercial_ba.valid_until IS NULL OR commercial_ba.valid_until > now())
                           )
                       )) THEN b.total_amount ELSE NULL END AS total_amount,
                       b.currency, b.assigned_admin_user_id,
                       false AS attributed_to_actor
                FROM bookings b
                WHERE (NOT :hasStatus OR b.status = :status)
                  AND (
                    :allScope
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
            .param("assignedScope", scopes.contains(Scope.ASSIGNED))
            .param("commercialAll", commercialScopes.contains(Scope.ALL))
            .param("commercialAssigned", commercialScopes.contains(Scope.ASSIGNED))
            .param("profileId", snapshot.profileId())
            .param("limit", pageSize)
            .query(BookingRow.class)
            .list().stream().map(BookingRow::view).toList();
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
        UUID assignedAdminUserId, boolean attributedToActor) {
        BookingView view() {
            return new BookingView(id, bookingCode, propertyId, status, checkInDate.toString(),
                checkOutDate.toString(), totalAmount == null ? null : totalAmount.toPlainString(), currency.trim());
        }
    }

    public record BookingView(UUID id, String bookingCode, UUID propertyId, String status,
        String checkInDate, String checkOutDate, String totalAmount, String currency) {}
}
