package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Scope;
import graphql.GraphqlErrorException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final UUID EMPTY_PARTITION = new UUID(0, 0);
    private static final String BOOKING_READ = "booking:read";
    private static final String SENSITIVE_READ = "booking-sensitive:read";

    private final JdbcClient jdbc;
    private final AuthorizationClient authorizations;
    private final AuthorizationContext authorizationContext;

    public BookingQuery(
        JdbcClient jdbc,
        AuthorizationClient authorizations,
        com.vistay.booking.application.BookingRecordScopePolicy ignoredCompatibilityPolicy,
        AuthorizationContext authorizationContext
    ) {
        this.jdbc = jdbc;
        this.authorizations = authorizations;
        this.authorizationContext = authorizationContext;
    }

    @QueryMapping
    @RequiresPermission(BOOKING_READ)
    public BookingView booking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id) {
        var actor = actor(jwt);
        var snapshot = subjectSnapshot(actor);
        var scopes = policyScopes(authorizationContext.require(jwt, BOOKING_READ));
        var sensitive = policyScopes(authorizationContext.scopes(jwt, SENSITIVE_READ));
        var branchIds = partitionIds(snapshot, BOOKING_READ, "BRANCH");
        var sensitiveBranchIds = partitionIds(snapshot, SENSITIVE_READ, "BRANCH");
        return jdbc.sql("""
                SELECT b.id, b.booking_code, b.property_id, b.status, b.check_in_date, b.check_out_date,
                       CASE WHEN :sensitiveAll
                           OR (:sensitiveSelf AND b.customer_id = :profileId)
                           OR (:sensitiveOwn AND b.property_snapshot ->> 'hostProfileId' = :profileIdText)
                           OR (:sensitiveAssigned AND (
                               b.assigned_admin_user_id = :profileId OR EXISTS (
                                   SELECT 1 FROM booking_attributions sensitive_ba
                                   WHERE sensitive_ba.booking_id = b.id
                                     AND sensitive_ba.admin_user_id = :profileId
                                     AND sensitive_ba.valid_from <= now()
                                     AND (sensitive_ba.valid_until IS NULL OR sensitive_ba.valid_until > now())
                               )
                           ))
                           OR (:sensitivePartition AND b.property_snapshot ->> 'branchId' IN (:sensitiveBranchIds))
                           THEN b.total_amount ELSE NULL END AS total_amount,
                       b.currency, b.created_at
                FROM bookings b
                WHERE b.id = :id AND (
                    :allScope
                    OR (:selfScope AND (b.customer_id = :profileId
                        OR (b.created_by_account_type = 'CUSTOMER' AND b.created_by_account_id = :accountId)))
                    OR (:ownScope AND b.property_snapshot ->> 'hostProfileId' = :profileIdText)
                    OR (:assignedScope AND (
                        b.assigned_admin_user_id = :profileId OR EXISTS (
                            SELECT 1 FROM booking_attributions ba
                            WHERE ba.booking_id = b.id AND ba.admin_user_id = :profileId
                              AND ba.valid_from <= now()
                              AND (ba.valid_until IS NULL OR ba.valid_until > now())
                        )
                    ))
                    OR (:partitionScope AND b.property_snapshot ->> 'branchId' IN (:branchIds))
                )
                """)
            .param("id", id)
            .param("accountId", actor.accountId())
            .param("profileId", snapshot.profileId())
            .param("profileIdText", snapshot.profileId().toString())
            .param("allScope", scopes.contains(Scope.ALL))
            .param("selfScope", scopes.contains(Scope.SELF))
            .param("ownScope", scopes.contains(Scope.OWN))
            .param("assignedScope", scopes.contains(Scope.ASSIGNED))
            .param("partitionScope", scopes.contains(Scope.PARTITION))
            .param("branchIds", sqlIds(branchIds))
            .param("sensitiveAll", sensitive.contains(Scope.ALL))
            .param("sensitiveSelf", sensitive.contains(Scope.SELF))
            .param("sensitiveOwn", sensitive.contains(Scope.OWN))
            .param("sensitiveAssigned", sensitive.contains(Scope.ASSIGNED))
            .param("sensitivePartition", sensitive.contains(Scope.PARTITION))
            .param("sensitiveBranchIds", sqlIds(sensitiveBranchIds))
            .query(BookingRow.class).optional()
            .map(BookingRow::view)
            .orElseThrow(() -> error("NOT_FOUND", "Booking was not found"));
    }

    /** Compatibility field; only ALL remains valid and the implementation is cursor-based. */
    @Deprecated(forRemoval = true)
    @QueryMapping
    @RequiresPermission(BOOKING_READ)
    public List<BookingView> bookings(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String status,
        @Argument Integer limit
    ) {
        return list(jwt, status, limit, null, Mode.ALL, null).nodes();
    }

    @QueryMapping
    @RequiresPermission(BOOKING_READ)
    public BookingConnection myBookings(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String status,
        @Argument Integer first,
        @Argument String after
    ) {
        return list(jwt, status, first, after, Mode.SELF, null);
    }

    @QueryMapping
    @RequiresPermission(BOOKING_READ)
    public BookingConnection assignedBookings(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String status,
        @Argument Integer first,
        @Argument String after
    ) {
        return list(jwt, status, first, after, Mode.ASSIGNED, null);
    }

    @QueryMapping
    @RequiresPermission(BOOKING_READ)
    public BookingConnection branchBookings(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID branchId,
        @Argument String status,
        @Argument Integer first,
        @Argument String after
    ) {
        return list(jwt, status, first, after, Mode.PARTITION, branchId);
    }

    @QueryMapping
    @RequiresPermission(BOOKING_READ)
    public BookingConnection ownedPropertyBookings(
        @AuthenticationPrincipal Jwt jwt,
        @Argument String status,
        @Argument Integer first,
        @Argument String after
    ) {
        return list(jwt, status, first, after, Mode.OWN, null);
    }

    private BookingConnection list(
        Jwt jwt,
        String status,
        Integer first,
        String after,
        Mode mode,
        UUID branchId
    ) {
        var actor = actor(jwt);
        var snapshot = subjectSnapshot(actor);
        var readScopes = policyScopes(authorizationContext.require(jwt, BOOKING_READ));
        requireScope(snapshot, readScopes, mode, branchId);
        var sensitive = policyScopes(authorizationContext.scopes(jwt, SENSITIVE_READ));
        var sensitiveBranches = partitionIds(snapshot, SENSITIVE_READ, "BRANCH");
        var cursor = BookingCursor.decode(after);
        var pageSize = first == null ? 50 : Math.max(1, Math.min(first, 100));
        var normalizedStatus = status == null ? "" : status.trim();
        var effectiveBranchId = branchId == null ? EMPTY_PARTITION : branchId;

        var rows = jdbc.sql("""
                SELECT b.id, b.booking_code, b.property_id, b.status, b.check_in_date, b.check_out_date,
                       CASE WHEN :sensitiveAll
                           OR (:sensitiveSelf AND :selfMode)
                           OR (:sensitiveOwn AND :ownMode)
                           OR (:sensitiveAssigned AND :assignedMode)
                           OR (:sensitivePartition AND :partitionMode
                               AND :branchIdText IN (:sensitiveBranchIds))
                           THEN b.total_amount ELSE NULL END AS total_amount,
                       b.currency, b.created_at
                FROM bookings b
                WHERE (NOT :hasStatus OR b.status = :status)
                  AND (NOT :hasCursor OR (b.created_at, b.id) < (:cursorCreatedAt, :cursorId))
                  AND (
                    (:allMode)
                    OR (:selfMode AND (b.customer_id = :profileId
                        OR (b.created_by_account_type = 'CUSTOMER' AND b.created_by_account_id = :accountId)))
                    OR (:ownMode AND b.property_snapshot ->> 'hostProfileId' = :profileIdText)
                    OR (:assignedMode AND (
                        b.assigned_admin_user_id = :profileId OR EXISTS (
                            SELECT 1 FROM booking_attributions ba
                            WHERE ba.booking_id = b.id AND ba.admin_user_id = :profileId
                              AND ba.valid_from <= now()
                              AND (ba.valid_until IS NULL OR ba.valid_until > now())
                        )
                    ))
                    OR (:partitionMode AND b.property_snapshot ->> 'branchId' = :branchIdText)
                  )
                ORDER BY b.created_at DESC, b.id DESC
                LIMIT :limit
                """)
            .param("hasStatus", !normalizedStatus.isBlank())
            .param("status", normalizedStatus)
            .param("hasCursor", cursor != null)
            .param("cursorCreatedAt", cursor == null ? OffsetDateTime.now() : cursor.createdAt())
            .param("cursorId", cursor == null ? EMPTY_PARTITION : cursor.id())
            .param("accountId", actor.accountId())
            .param("profileId", snapshot.profileId())
            .param("profileIdText", snapshot.profileId().toString())
            .param("branchIdText", effectiveBranchId.toString())
            .param("allMode", mode == Mode.ALL)
            .param("selfMode", mode == Mode.SELF)
            .param("ownMode", mode == Mode.OWN)
            .param("assignedMode", mode == Mode.ASSIGNED)
            .param("partitionMode", mode == Mode.PARTITION)
            .param("sensitiveAll", sensitive.contains(Scope.ALL))
            .param("sensitiveSelf", sensitive.contains(Scope.SELF))
            .param("sensitiveOwn", sensitive.contains(Scope.OWN))
            .param("sensitiveAssigned", sensitive.contains(Scope.ASSIGNED))
            .param("sensitivePartition", sensitive.contains(Scope.PARTITION))
            .param("sensitiveBranchIds", sqlIds(sensitiveBranches))
            .param("limit", pageSize + 1)
            .query(BookingRow.class).list();

        var hasNext = rows.size() > pageSize;
        var pageRows = hasNext ? rows.subList(0, pageSize) : rows;
        var nodes = pageRows.stream().map(BookingRow::view).toList();
        var endCursor = pageRows.isEmpty() ? null : BookingCursor.encode(
            pageRows.getLast().createdAt(), pageRows.getLast().id());
        return new BookingConnection(nodes, new PageInfo(hasNext, endCursor));
    }

    private static void requireScope(
        AuthorizationClient.Snapshot snapshot,
        Set<Scope> scopes,
        Mode mode,
        UUID branchId
    ) {
        var required = switch (mode) {
            case ALL -> Scope.ALL;
            case SELF -> Scope.SELF;
            case OWN -> Scope.OWN;
            case ASSIGNED -> Scope.ASSIGNED;
            case PARTITION -> Scope.PARTITION;
        };
        if (!scopes.contains(required)) {
            throw error("FORBIDDEN_SCOPE", "Booking projection is outside the authorized scope");
        }
        if (mode == Mode.PARTITION && (branchId == null
            || !partitionIds(snapshot, BOOKING_READ, "BRANCH").contains(branchId.toString()))) {
            throw error("FORBIDDEN_SCOPE", "Branch is outside the authorized partition");
        }
    }

    private AuthorizationClient.Snapshot subjectSnapshot(Actor actor) {
        var snapshot = authorizations.get(actor.accountType(), actor.accountId());
        if (!snapshot.active() || snapshot.profileId() == null) {
            throw error("PROFILE_INACTIVE", "Profile is inactive");
        }
        return snapshot;
    }

    private static Set<Scope> policyScopes(Set<String> scopes) {
        return scopes.stream().map(Scope::valueOf)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static Set<String> partitionIds(
        AuthorizationClient.Snapshot snapshot,
        String permission,
        String type
    ) {
        return snapshot.partitions(permission).stream()
            .filter(partition -> type.equals(partition.type()))
            .map(partition -> partition.id().toString())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static List<String> sqlIds(Set<String> ids) {
        return ids.isEmpty() ? List.of(EMPTY_PARTITION.toString()) : new ArrayList<>(ids);
    }

    private static Actor actor(Jwt jwt) {
        if (jwt == null) throw error("UNAUTHENTICATED", "Authentication is required");
        try {
            var accountType = jwt.getClaimAsString("account_type");
            return new Actor(UUID.fromString(jwt.getSubject()),
                accountType == null ? "ADMIN" : accountType.trim().toUpperCase());
        } catch (RuntimeException invalid) {
            throw error("INVALID_TOKEN", "Token identity is invalid");
        }
    }

    private static GraphqlErrorException error(String code, String message) {
        var classification = code.startsWith("FORBIDDEN") ? ErrorType.FORBIDDEN
            : code.endsWith("NOT_FOUND") || code.equals("NOT_FOUND") ? ErrorType.NOT_FOUND
            : ErrorType.BAD_REQUEST;
        return GraphqlErrorException.newErrorException().message(message)
            .errorClassification(classification).extensions(Map.of("code", code)).build();
    }

    private enum Mode { SELF, OWN, ASSIGNED, PARTITION, ALL }
    private record Actor(UUID accountId, String accountType) {}
    private record BookingRow(UUID id, String bookingCode, UUID propertyId, String status,
        LocalDate checkInDate, LocalDate checkOutDate, BigDecimal totalAmount, String currency,
        OffsetDateTime createdAt) {
        BookingView view() {
            return new BookingView(id, bookingCode, propertyId, status, checkInDate.toString(),
                checkOutDate.toString(), totalAmount == null ? null : totalAmount.toPlainString(), currency.trim());
        }
    }

    public record BookingView(UUID id, String bookingCode, UUID propertyId, String status,
        String checkInDate, String checkOutDate, String totalAmount, String currency) {}
    public record BookingConnection(List<BookingView> nodes, PageInfo pageInfo) {}
    public record PageInfo(boolean hasNextPage, String endCursor) {}
}
