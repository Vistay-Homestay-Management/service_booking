package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.PropertyPartitionClient;
import com.vistay.booking.observability.BookingBusinessMetrics;
import graphql.GraphqlErrorException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;

@Controller
public class BookingCommandController {
    private final JdbcClient jdbc;
    private final PropertyPartitionClient partitions;
    private final BookingBusinessMetrics metrics;

    public BookingCommandController(JdbcClient jdbc, PropertyPartitionClient partitions, BookingBusinessMetrics metrics) {
        this.jdbc = jdbc;
        this.partitions = partitions;
        this.metrics = metrics;
    }

    @MutationMapping
    @Transactional
    @RequiresPermission("booking:create")
    public BookingQuery.BookingView createBooking(@AuthenticationPrincipal Jwt jwt, @Argument CreateBookingInput input) {
        var actor = actor(jwt);
        var replay = jdbc.sql("""
                SELECT resource_id FROM command_idempotency
                WHERE idempotency_key = :key AND command_type = 'CREATE_BOOKING' AND status = 'SUCCEEDED'
                """).param("key", required(input.idempotencyKey(), "idempotencyKey")).query(UUID.class).optional();
        if (replay.isPresent()) return view(replay.get());

        var partition = partitions.resolve(input.propertyId());
        if (partition.status() == PropertyPartitionClient.Status.UNAVAILABLE) throw bad("PROPERTY_PARTITION_UNAVAILABLE", "Property partition authority is unavailable");
        if (partition.status() == PropertyPartitionClient.Status.NOT_FOUND) throw bad("PROPERTY_NOT_FOUND", "Property was not found");
        if (partition.status() != PropertyPartitionClient.Status.MAPPED) throw bad("PROPERTY_PARTITION_UNMAPPED", "Property is not assigned to an operating branch");
        if (!input.checkOutDate().isAfter(input.checkInDate())) throw bad("INVALID_BOOKING_DATES", "Check-out must be after check-in");
        var nights = ChronoUnit.DAYS.between(input.checkInDate(), input.checkOutDate());
        var unitPrice = money(input.unitPrice(), "unitPrice");
        var discount = moneyOrZero(input.discountAmount());
        var fee = moneyOrZero(input.feeAmount());
        var tax = moneyOrZero(input.taxAmount());
        var subtotal = unitPrice.multiply(BigDecimal.valueOf(nights));
        var total = subtotal.subtract(discount).add(fee).add(tax);
        if (total.signum() < 0) throw bad("INVALID_BOOKING_AMOUNT", "Booking total cannot be negative");
        var id = UUID.randomUUID();
        var code = "VS-" + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "") + "-" + id.toString().substring(0, 6).toUpperCase();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            jdbc.sql("""
                    INSERT INTO bookings(id, booking_code, property_id, customer_id, source_channel, stay_type, status,
                      check_in_date, check_out_date, adults, children, guest_name, guest_phone, guest_email,
                      guest_snapshot, property_snapshot, price_unit, duration_value, unit_price_snapshot,
                      subtotal_amount, discount_amount, fee_amount, tax_amount, total_amount, currency,
                      created_by_account_type, created_by_account_id, notes)
                    VALUES (:id, :code, :propertyId, :customerId, 'ADMIN_PORTAL', :stayType, 'PENDING_CONFIRMATION',
                      :checkIn, :checkOut, :adults, :children, :guestName, :guestPhone, :guestEmail,
                      jsonb_build_object('profileId', CAST(:customerText AS text), 'name', CAST(:guestName AS text), 'email', CAST(:guestEmail AS text)),
                      jsonb_build_object('propertyName', CAST(:propertyName AS text), 'hostProfileId', CAST(:hostText AS text),
                        'branchId', CAST(:branchText AS text), 'membershipVersion', :membershipVersion),
                      'NIGHT', :nights, :unitPrice, :subtotal, :discount, :fee, :tax, :total, :currency,
                      'ADMIN', :actorId, :notes)
                    """).param("id", id).param("code", code).param("propertyId", input.propertyId())
                .param("customerId", input.customerProfileId()).param("stayType", stayType(input.stayType()))
                .param("checkIn", input.checkInDate()).param("checkOut", input.checkOutDate())
                .param("adults", Math.max(1, input.adults() == null ? 1 : input.adults()))
                .param("children", Math.max(0, input.children() == null ? 0 : input.children()))
                .param("guestName", required(input.guestName(), "guestName")).param("guestPhone", input.guestPhone() == null ? "" : input.guestPhone().trim())
                .param("guestEmail", blank(input.guestEmail())).param("customerText", input.customerProfileId() == null ? "" : input.customerProfileId().toString())
                .param("propertyName", required(input.propertyName(), "propertyName"))
                .param("hostText", input.hostProfileId() == null ? "" : input.hostProfileId().toString())
                .param("branchText", partition.branchId().toString()).param("membershipVersion", partition.membershipVersion())
                .param("nights", BigDecimal.valueOf(nights)).param("unitPrice", unitPrice).param("subtotal", subtotal)
                .param("discount", discount).param("fee", fee).param("tax", tax).param("total", total)
                .param("currency", currency(input.currency())).param("actorId", actor).param("notes", blank(input.notes())).update();
            jdbc.sql("""
                    INSERT INTO inventory_blocks(property_id, booking_id, block_type, status, start_date, end_date_exclusive, reason, created_by_admin_user_id)
                    VALUES (:propertyId, :bookingId, 'BOOKING', 'TENTATIVE', :checkIn, :checkOut, 'Booking pending confirmation', :actorId)
                    """).param("propertyId", input.propertyId()).param("bookingId", id).param("checkIn", input.checkInDate())
                .param("checkOut", input.checkOutDate()).param("actorId", actor).update();
        } catch (DataIntegrityViolationException conflict) {
            throw bad("PROPERTY_NOT_AVAILABLE", "Property is not available for the selected dates");
        }
        history(id, null, "PENDING_CONFIRMATION", "Booking created", actor);
        audit(actor, "booking.created", id);
        outbox(id, 0, "booking.created.v1");
        jdbc.sql("""
                INSERT INTO command_idempotency(idempotency_key, command_type, actor_account_type, actor_account_id,
                  request_hash, resource_type, resource_id, response_code, status, expires_at, completed_at)
                VALUES (:key, 'CREATE_BOOKING', 'ADMIN', :actor, repeat('0', 64), 'BOOKING', :id, 200, 'SUCCEEDED', now() + interval '7 days', now())
                """).param("key", input.idempotencyKey()).param("actor", actor).param("id", id).update();
        var result = view(id);
        metrics.commandSucceeded("create");
        return result;
    }

    @MutationMapping @Transactional @RequiresPermission("booking:update")
    public BookingQuery.BookingView confirmBooking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id, @Argument String expectedVersion, @Argument String reason) {
        return transition(jwt, id, expectedVersion, reason, "CONFIRMED", "PENDING_CONFIRMATION", "HELD");
    }

    @MutationMapping @Transactional @RequiresPermission("booking:check-in")
    public BookingQuery.BookingView checkInBooking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id, @Argument String expectedVersion, @Argument String reason) {
        return transition(jwt, id, expectedVersion, reason, "CHECKED_IN", "CONFIRMED");
    }

    @MutationMapping @Transactional @RequiresPermission("booking:check-out")
    public BookingQuery.BookingView checkOutBooking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id, @Argument String expectedVersion, @Argument String reason) {
        return transition(jwt, id, expectedVersion, reason, "COMPLETED", "CHECKED_IN");
    }

    @MutationMapping @Transactional @RequiresPermission("booking:cancel")
    public BookingQuery.BookingView cancelBooking(@AuthenticationPrincipal Jwt jwt, @Argument UUID id, @Argument String expectedVersion, @Argument String reason) {
        return transition(jwt, id, expectedVersion, reason, "CANCELLED", "PENDING_CONFIRMATION", "HELD", "CONFIRMED");
    }

    private BookingQuery.BookingView transition(Jwt jwt, UUID id, String expectedVersion, String reason, String target, String... allowed) {
        var actor = actor(jwt);
        var why = required(reason, "reason");
        var previous = jdbc.sql("SELECT status FROM bookings WHERE id = :id").param("id", id).query(String.class).optional()
            .orElseThrow(() -> bad("BOOKING_NOT_FOUND", "Booking was not found"));
        if (java.util.Arrays.stream(allowed).noneMatch(previous::equals)) throw bad("INVALID_BOOKING_TRANSITION", "Booking cannot transition from " + previous + " to " + target);
        var version = parseVersion(expectedVersion);
        var timestamp = switch (target) {
            case "CONFIRMED" -> "confirmed_at = now(),";
            case "CHECKED_IN" -> "checked_in_at = now(),";
            case "COMPLETED" -> "completed_at = now(),";
            case "CANCELLED" -> "cancelled_at = now(), cancellation_reason = :reason,";
            default -> "";
        };
        var updated = jdbc.sql("UPDATE bookings SET status = :target, " + timestamp + " version = version + 1, updated_at = now() WHERE id = :id AND version = :version")
            .param("target", target).param("reason", why).param("id", id).param("version", version).update();
        if (updated != 1) throw bad("STALE_BOOKING_VERSION", "Booking was updated by another request");
        if ("CONFIRMED".equals(target)) jdbc.sql("UPDATE inventory_blocks SET status = 'ACTIVE', version = version + 1, updated_at = now() WHERE booking_id = :id AND status = 'TENTATIVE'").param("id", id).update();
        if ("COMPLETED".equals(target) || "CANCELLED".equals(target)) jdbc.sql("UPDATE inventory_blocks SET status = 'RELEASED', released_at = now(), version = version + 1, updated_at = now() WHERE booking_id = :id AND status IN ('TENTATIVE','ACTIVE')").param("id", id).update();
        history(id, previous, target, why, actor);
        audit(actor, "booking." + target.toLowerCase(), id);
        outbox(id, version + 1, "booking.status-changed.v1");
        var result = view(id);
        metrics.commandSucceeded(switch (target) {
            case "CONFIRMED" -> "confirm";
            case "CHECKED_IN" -> "check_in";
            case "COMPLETED" -> "check_out";
            case "CANCELLED" -> "cancel";
            default -> target.toLowerCase();
        });
        return result;
    }

    private BookingQuery.BookingView view(UUID id) {
        return jdbc.sql("""
                SELECT id, booking_code, property_id, customer_id, guest_name, guest_phone, guest_email,
                       property_snapshot ->> 'propertyName' property_name,
                       NULLIF(property_snapshot ->> 'branchId', '')::uuid branch_id,
                       status, check_in_date, check_out_date, total_amount, currency, created_at, version
                FROM bookings WHERE id = :id
                """).param("id", id).query((rs, row) -> new BookingQuery.BookingView(rs.getObject("id", UUID.class), rs.getString("booking_code"),
                    rs.getObject("property_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getString("guest_name"),
                    rs.getString("guest_phone"), rs.getString("guest_email"), rs.getString("property_name"), rs.getObject("branch_id", UUID.class),
                    rs.getString("status"), rs.getObject("check_in_date", LocalDate.class).toString(), rs.getObject("check_out_date", LocalDate.class).toString(),
                    rs.getBigDecimal("total_amount").toPlainString(), rs.getString("currency").trim(), rs.getObject("created_at", OffsetDateTime.class).toString(),
                    Long.toString(rs.getLong("version")))).single();
    }

    private void history(UUID id, String previous, String next, String reason, UUID actor) {
        jdbc.sql("INSERT INTO booking_status_history(booking_id, previous_status, new_status, reason, actor_account_type, actor_account_id) VALUES (:id, :previous, :next, :reason, 'ADMIN', :actor)")
            .param("id", id).param("previous", previous == null ? "" : previous).param("next", next).param("reason", reason).param("actor", actor).update();
    }
    private void audit(UUID actor, String action, UUID id) {
        jdbc.sql("INSERT INTO audit_events(actor_account_type, actor_account_id, action, aggregate_type, aggregate_id) VALUES ('ADMIN', :actor, :action, 'BOOKING', :id)")
            .param("actor", actor).param("action", action).param("id", id).update();
    }
    private void outbox(UUID id, long version, String event) {
        jdbc.sql("INSERT INTO outbox_events(aggregate_type, aggregate_id, aggregate_version, event_type, payload) VALUES ('BOOKING', :id, :version, :event, jsonb_build_object('bookingId', CAST(:idText AS text)))")
            .param("id", id).param("version", version).param("event", event).param("idText", id.toString()).update();
    }
    private static UUID actor(Jwt jwt) {
        if (jwt == null) throw bad("UNAUTHENTICATED", "Authentication is required");
        try { return UUID.fromString(jwt.getSubject()); } catch (RuntimeException invalid) { throw bad("INVALID_TOKEN", "Token subject is invalid"); }
    }
    private static String required(String value, String field) { if (value == null || value.trim().isEmpty()) throw bad("VALIDATION_ERROR", field + " is required"); return value.trim(); }
    private static String blank(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private static BigDecimal money(String value, String field) { try { var result = new BigDecimal(required(value, field)); if (result.signum() < 0) throw new NumberFormatException(); return result; } catch (NumberFormatException invalid) { throw bad("INVALID_AMOUNT", field + " must be a non-negative amount"); } }
    private static BigDecimal moneyOrZero(String value) { return value == null || value.isBlank() ? BigDecimal.ZERO : money(value, "amount"); }
    private static String stayType(String value) { var result = value == null ? "SHORT_TERM" : value.trim().toUpperCase(); if (!java.util.Set.of("SHORT_TERM","MEDIUM_TERM","LONG_TERM").contains(result)) throw bad("INVALID_STAY_TYPE", "Stay type is invalid"); return result; }
    private static String currency(String value) { var result = value == null ? "VND" : value.trim().toUpperCase(); if (!result.matches("[A-Z]{3}")) throw bad("INVALID_CURRENCY", "Currency is invalid"); return result; }
    private static long parseVersion(String value) { try { return Long.parseLong(value); } catch (RuntimeException invalid) { throw bad("INVALID_VERSION", "Expected version is invalid"); } }
    private static GraphqlErrorException bad(String code, String message) { return GraphqlErrorException.newErrorException().message(message).errorClassification(ErrorType.BAD_REQUEST).extensions(Map.of("code", code)).build(); }

    public record CreateBookingInput(String idempotencyKey, UUID propertyId, String propertyName, UUID hostProfileId, UUID customerProfileId,
        String guestName, String guestPhone, String guestEmail, LocalDate checkInDate, LocalDate checkOutDate, Integer adults,
        Integer children, String stayType, String unitPrice, String discountAmount, String feeAmount, String taxAmount, String currency, String notes) {}
}
