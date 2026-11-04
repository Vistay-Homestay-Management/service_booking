package com.vistay.booking.application;

import com.vistay.booking.application.InventoryScopePolicy.ScopeEvidence;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InventoryAvailabilityService {
    private static final Set<String> BLOCK_TYPES = Set.of(
        "BOOKING", "MAINTENANCE", "CLEANING", "OWNER_USE", "MANUAL", "CHANNEL");
    private static final Set<String> BLOCK_STATUSES = Set.of(
        "TENTATIVE", "ACTIVE", "RELEASED", "CANCELLED");
    private final JdbcClient jdbc;
    private final Clock clock;
    private final TransactionTemplate repeatableRead;

    public InventoryAvailabilityService(
        JdbcClient jdbc,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.repeatableRead = new TransactionTemplate(transactionManager);
        this.repeatableRead.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.repeatableRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.repeatableRead.setReadOnly(true);
        this.repeatableRead.setName("inventory-availability-observation");
    }

    public Availability observe(
        UUID propertyId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        ScopeEvidence propertyAuthority
    ) {
        requireRange(propertyId, checkInDate, checkOutDate, propertyAuthority);
        final Availability result;
        try {
            result = repeatableRead.execute(status -> readSnapshot(
                propertyId, checkInDate, checkOutDate, propertyAuthority));
        } catch (DataAccessException | TransactionException failure) {
            throw new PricingPolicyException("INVENTORY_OBSERVATION_FAILED",
                "Inventory observation could not be completed");
        }
        if (result == null) {
            throw new PricingPolicyException("INVENTORY_OBSERVATION_FAILED",
                "Inventory observation could not be completed");
        }
        return result;
    }

    private Availability readSnapshot(
        UUID propertyId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        ScopeEvidence propertyAuthority
    ) {
        var rows = jdbc.sql("""
                WITH inventory_snapshot AS (
                    SELECT COALESCE((
                        SELECT revision
                        FROM inventory_property_revisions
                        WHERE property_id = :propertyId
                    ), 0)::bigint AS inventory_revision,
                    current_setting('transaction_isolation') AS isolation_level
                ), overlapping_blocks AS (
                    SELECT block_type, status, start_date, end_date_exclusive,
                        CASE WHEN block_type = 'CHANNEL' THEN channel_provider END
                            AS channel_provider
                    FROM inventory_blocks
                    WHERE property_id = :propertyId
                      AND daterange(start_date, end_date_exclusive, '[)')
                          && daterange(CAST(:checkInDate AS date),
                              CAST(:checkOutDate AS date), '[)')
                )
                SELECT snapshot.inventory_revision, snapshot.isolation_level,
                    block.block_type, block.status, block.start_date,
                    block.end_date_exclusive, block.channel_provider
                FROM inventory_snapshot snapshot
                LEFT JOIN overlapping_blocks block ON true
                ORDER BY block.start_date NULLS LAST,
                    block.end_date_exclusive NULLS LAST,
                    block.block_type NULLS LAST,
                    block.status NULLS LAST,
                    block.channel_provider NULLS LAST
                """)
            .param("propertyId", propertyId)
            .param("checkInDate", checkInDate)
            .param("checkOutDate", checkOutDate)
            .query((resultSet, rowNumber) -> new SnapshotRow(
                resultSet.getLong("inventory_revision"),
                resultSet.getString("isolation_level"),
                resultSet.getString("block_type"),
                resultSet.getString("status"),
                resultSet.getObject("start_date", LocalDate.class),
                resultSet.getObject("end_date_exclusive", LocalDate.class),
                resultSet.getString("channel_provider")
            ))
            .list();

        if (rows.isEmpty()) {
            throw new PricingPolicyException("INVENTORY_OBSERVATION_FAILED",
                "Inventory snapshot returned no observation");
        }
        var first = rows.getFirst();
        if (first.inventoryRevision() < 0
            || !"repeatable read".equals(first.isolationLevel())) {
            throw invalidStoredEvidence();
        }
        List<OverlapEvidence> overlaps = new ArrayList<>();
        for (var row : rows) {
            if (row.blockType() != null) {
                if (!BLOCK_TYPES.contains(row.blockType())
                    || !BLOCK_STATUSES.contains(row.status())
                    || row.startDate() == null || row.endDateExclusive() == null
                    || !row.endDateExclusive().isAfter(row.startDate())
                    || ("CHANNEL".equals(row.blockType())
                        ? row.channelProvider() == null || row.channelProvider().isBlank()
                        : row.channelProvider() != null)) {
                    throw invalidStoredEvidence();
                }
                overlaps.add(new OverlapEvidence(row.blockType(), row.status(),
                    row.startDate(), row.endDateExclusive(), row.channelProvider()));
            }
        }
        var available = overlaps.stream().noneMatch(overlap ->
            "TENTATIVE".equals(overlap.status()) || "ACTIVE".equals(overlap.status()));
        return new Availability(propertyId, checkInDate, checkOutDate, available,
            first.inventoryRevision(), propertyAuthority.propertyBranchId(),
            propertyAuthority.propertyMembershipVersion(), clock.instant(),
            List.copyOf(overlaps));
    }

    private void requireRange(
        UUID propertyId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        ScopeEvidence propertyAuthority
    ) {
        if (propertyId == null || propertyAuthority == null
            || propertyAuthority.propertyBranchId() == null
            || propertyAuthority.propertyMembershipVersion() < 0) {
            throw new PricingPolicyException("PROPERTY_NOT_FOUND",
                "An authoritative Property partition is required");
        }
        if (checkInDate == null || checkOutDate == null
            || !checkOutDate.isAfter(checkInDate)) {
            throw new PricingPolicyException("INVALID_INVENTORY_RANGE",
                "Checkout must be after check-in for a half-open date range");
        }
    }

    private PricingPolicyException invalidStoredEvidence() {
        return new PricingPolicyException("INVENTORY_OBSERVATION_FAILED",
            "Stored inventory evidence is invalid");
    }

    public record Availability(
        UUID propertyId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        boolean available,
        long inventoryRevision,
        UUID branchId,
        long propertyMembershipVersion,
        Instant observedAt,
        List<OverlapEvidence> overlaps
    ) {}

    public record OverlapEvidence(
        String type,
        String status,
        LocalDate startDate,
        LocalDate endDateExclusive,
        String channelProvider
    ) {}

    private record SnapshotRow(
        long inventoryRevision,
        String isolationLevel,
        String blockType,
        String status,
        LocalDate startDate,
        LocalDate endDateExclusive,
        String channelProvider
    ) {}
}
