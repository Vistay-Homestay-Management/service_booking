package com.vistay.booking.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryBlockManagementService {
    private static final Set<String> TYPES = Set.of(
        "BOOKING", "MAINTENANCE", "CLEANING", "OWNER_USE", "MANUAL", "CHANNEL");
    private static final Set<String> STATUSES = Set.of(
        "TENTATIVE", "ACTIVE", "RELEASED", "CANCELLED");

    private final JdbcClient jdbc;

    public InventoryBlockManagementService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page list(Query query) {
        validate(query);
        try {
            var cursor = decodeCursor(query.after());
            var sql = new StringBuilder("""
                    SELECT id, property_id, block_type, status, start_date,
                           end_date_exclusive, reason, channel_provider, version,
                           created_at, updated_at, released_at
                    FROM inventory_blocks
                    WHERE property_id = :propertyId
                    """);
            var parameters = new LinkedHashMap<String, Object>();
            parameters.put("propertyId", query.propertyId());
            if (query.fromDate() != null && query.toDateExclusive() != null) {
                sql.append(" AND daterange(start_date, end_date_exclusive, '[)') && daterange(:fromDate, :toDateExclusive, '[)')");
                parameters.put("fromDate", query.fromDate());
                parameters.put("toDateExclusive", query.toDateExclusive());
            } else if (query.fromDate() != null) {
                sql.append(" AND end_date_exclusive > :fromDate");
                parameters.put("fromDate", query.fromDate());
            } else if (query.toDateExclusive() != null) {
                sql.append(" AND start_date < :toDateExclusive");
                parameters.put("toDateExclusive", query.toDateExclusive());
            }
            appendInFilter(sql, parameters, "block_type", "type", query.types());
            appendInFilter(sql, parameters, "status", "status", query.statuses());
            if (cursor != null) {
                sql.append("""
                         AND (updated_at < :cursorUpdatedAt
                              OR (updated_at = :cursorUpdatedAt AND id < :cursorId))
                        """);
                parameters.put("cursorUpdatedAt", cursor.updatedAt());
                parameters.put("cursorId", cursor.id());
            }
            sql.append(" ORDER BY updated_at DESC, id DESC LIMIT :limit");
            parameters.put("limit", query.first() + 1);

            var rows = jdbc.sql(sql.toString()).params(parameters)
                .query((rs, rowNumber) -> {
                    var updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
                    return new Row(
                        rs.getObject("id", UUID.class),
                        rs.getObject("property_id", UUID.class),
                        rs.getString("block_type"), rs.getString("status"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("end_date_exclusive", LocalDate.class),
                        rs.getString("reason"), rs.getString("channel_provider"),
                        rs.getLong("version"),
                        rs.getObject("created_at", OffsetDateTime.class), updatedAt,
                        rs.getObject("released_at", OffsetDateTime.class));
                }).list();

            boolean hasNextPage = rows.size() > query.first();
            var selected = rows.subList(0, Math.min(rows.size(), query.first()));
            var nodes = selected.stream().map(Row::toNode).toList();
            var endCursor = selected.isEmpty() ? null : encodeCursor(
                selected.get(selected.size() - 1).updatedAt(),
                selected.get(selected.size() - 1).id());
            var revision = jdbc.sql("""
                    SELECT revision FROM inventory_property_revisions
                    WHERE property_id = :propertyId
                    """)
                .param("propertyId", query.propertyId())
                .query(Long.class).optional().orElse(0L);
            return new Page(List.copyOf(nodes), new PageInfo(hasNextPage, endCursor), revision);
        } catch (PricingPolicyException expected) {
            throw expected;
        } catch (DataAccessException unavailable) {
            throw new PricingPolicyException("INVENTORY_MANAGEMENT_UNAVAILABLE",
                "Inventory management projection is unavailable");
        }
    }

    private static void appendInFilter(
        StringBuilder sql,
        LinkedHashMap<String, Object> parameters,
        String column,
        String prefix,
        List<String> values
    ) {
        if (values.isEmpty()) return;
        var placeholders = new ArrayList<String>();
        for (int index = 0; index < values.size(); index++) {
            var key = prefix + index;
            placeholders.add(":" + key);
            parameters.put(key, values.get(index));
        }
        sql.append(" AND ").append(column).append(" IN (")
            .append(String.join(", ", placeholders)).append(')');
    }

    private static void validate(Query query) {
        if (query == null || query.propertyId() == null || query.first() < 1
            || query.first() > 100 || query.types() == null || query.statuses() == null
            || !TYPES.containsAll(query.types()) || !STATUSES.containsAll(query.statuses())
            || query.types().size() != Set.copyOf(query.types()).size()
            || query.statuses().size() != Set.copyOf(query.statuses()).size()
            || (query.fromDate() != null && query.toDateExclusive() != null
                && !query.toDateExclusive().isAfter(query.fromDate()))) {
            throw invalidInput();
        }
    }

    static String encodeCursor(OffsetDateTime updatedAt, UUID id) {
        var raw = updatedAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decodeCursor(String value) {
        if (value == null) return null;
        if (value.isBlank() || value.length() > 180) throw invalidCursor();
        try {
            var raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            var parts = raw.split("\\|", -1);
            if (parts.length != 2) throw invalidCursor();
            var result = new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
            if (!encodeCursor(result.updatedAt(), result.id()).equals(value)) throw invalidCursor();
            return result;
        } catch (IllegalArgumentException invalid) {
            throw invalidCursor();
        }
    }

    private static PricingPolicyException invalidInput() {
        return new PricingPolicyException("INVALID_INVENTORY_MANAGEMENT_INPUT",
            "Inventory management filters are invalid");
    }

    private static PricingPolicyException invalidCursor() {
        return new PricingPolicyException("INVALID_INVENTORY_CURSOR",
            "Inventory management cursor is invalid");
    }

    public record Query(
        UUID propertyId,
        LocalDate fromDate,
        LocalDate toDateExclusive,
        List<String> types,
        List<String> statuses,
        int first,
        String after
    ) {}

    public record Page(List<Block> nodes, PageInfo pageInfo, long inventoryRevision) {}

    public record PageInfo(boolean hasNextPage, String endCursor) {}

    public record Block(
        String inventoryBlockId,
        String propertyId,
        String type,
        String status,
        String startDate,
        String endDateExclusive,
        String reason,
        String channelProvider,
        String blockVersion,
        String createdAt,
        String updatedAt,
        String releasedAt
    ) {}

    record Cursor(OffsetDateTime updatedAt, UUID id) {}

    private record Row(
        UUID id,
        UUID propertyId,
        String type,
        String status,
        LocalDate startDate,
        LocalDate endDateExclusive,
        String reason,
        String channelProvider,
        long version,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime releasedAt
    ) {
        Block toNode() {
            return new Block(id.toString(), propertyId.toString(), type, status,
                startDate.toString(), endDateExclusive.toString(), reason, channelProvider,
                Long.toString(version), createdAt.toString(), updatedAt.toString(),
                releasedAt == null ? null : releasedAt.toString());
        }
    }
}
