package com.vistay.booking.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryReconciliationService {
    private final JdbcClient jdbc;
    private final Clock clock;

    public InventoryReconciliationService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot reconcile(UUID propertyId, long consumerRevision) {
        long head = jdbc.sql("""
                SELECT revision FROM inventory_property_revisions
                WHERE property_id = :propertyId
                """)
            .param("propertyId", propertyId)
            .query(Long.class)
            .optional()
            .orElse(0L);
        if (consumerRevision > head) {
            throw new PricingPolicyException("INVENTORY_RECONCILIATION_AHEAD",
                "Consumer inventory revision is ahead of the Booking-owned head");
        }

        var observedAt = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
        if (consumerRevision == head) {
            return new Snapshot(propertyId, head, true, List.of(), observedAt);
        }

        var blocks = jdbc.sql("""
                SELECT id, block_type, status, start_date, end_date_exclusive,
                       version, channel_provider
                FROM inventory_blocks
                WHERE property_id = :propertyId
                  AND status IN ('TENTATIVE', 'ACTIVE')
                ORDER BY start_date, end_date_exclusive, block_type, status, id
                """)
            .param("propertyId", propertyId)
            .query((rs, rowNumber) -> new Block(
                rs.getObject("id", UUID.class),
                rs.getString("block_type"),
                rs.getString("status"),
                rs.getObject("start_date", java.time.LocalDate.class),
                rs.getObject("end_date_exclusive", java.time.LocalDate.class),
                rs.getLong("version"),
                rs.getString("channel_provider")))
            .list();
        return new Snapshot(propertyId, head, false, List.copyOf(blocks), observedAt);
    }

    public record Snapshot(
        UUID propertyId,
        long inventoryRevision,
        boolean notModified,
        List<Block> blocks,
        OffsetDateTime observedAt
    ) {}

    public record Block(
        UUID id,
        String type,
        String status,
        java.time.LocalDate startDate,
        java.time.LocalDate endDateExclusive,
        long version,
        String channelProvider
    ) {}
}
