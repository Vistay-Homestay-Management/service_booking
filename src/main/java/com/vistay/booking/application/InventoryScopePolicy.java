package com.vistay.booking.application;

import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InventoryScopePolicy {
    private static final java.util.Set<String> GLOBAL_ROLES =
        java.util.Set.of("SUPER_ADMIN", "CTV");
    private static final java.util.Set<String> BRANCH_ROLES =
        java.util.Set.of("MANAGER", "RECEPTIONIST", "STAFF");
    private final PropertyPartitionClient properties;

    public InventoryScopePolicy(PropertyPartitionClient properties) {
        this.properties = properties;
    }

    /**
     * Inventory always resolves Property authority, including for ALL grants.
     * This prevents a stale/global grant from turning an unknown property UUID
     * into a false "available" observation.
     */
    public ScopeEvidence require(
        AuthorizationClient.Snapshot snapshot,
        String permission,
        UUID propertyId
    ) {
        if (propertyId == null) {
            throw new PricingPolicyException("PROPERTY_NOT_FOUND", "Property is missing");
        }
        var resolution = properties.resolve(propertyId);
        if (resolution.status() == PropertyPartitionClient.Status.UNAVAILABLE) {
            throw new PricingPolicyException("PROPERTY_PARTITION_UNAVAILABLE",
                "Property partition authority is unavailable");
        }
        if (resolution.status() == PropertyPartitionClient.Status.NOT_FOUND) {
            throw new PricingPolicyException("PROPERTY_NOT_FOUND",
                "Property does not exist");
        }
        if (resolution.status() == PropertyPartitionClient.Status.UNMAPPED) {
            throw new PricingPolicyException("PROPERTY_PARTITION_UNMAPPED",
                "Property has no active branch partition");
        }
        if (resolution.status() == PropertyPartitionClient.Status.MAPPED
            && (resolution.branchId() == null || resolution.membershipVersion() < 0)) {
            throw new PricingPolicyException("PROPERTY_PARTITION_UNAVAILABLE",
                "Property partition authority returned malformed evidence");
        }
        if (resolution.status() != PropertyPartitionClient.Status.MAPPED) {
            throw new PricingPolicyException("PROPERTY_PARTITION_UNAVAILABLE",
                "Property partition authority returned an invalid resolution");
        }

        var scopes = snapshot.scopes(permission);
        var branchBound = snapshot.roles().stream().anyMatch(BRANCH_ROLES::contains);
        var global = snapshot.roles().stream().anyMatch(GLOBAL_ROLES::contains);
        if (!branchBound && global && scopes.contains(Scope.ALL)) {
            return new ScopeEvidence(
                "ALL", null, resolution.branchId(), resolution.membershipVersion());
        }
        if (branchBound && scopes.contains(Scope.PARTITION)) {
            var allowed = snapshot.partitions(permission).stream().anyMatch(partition ->
                "BRANCH".equals(partition.type())
                    && resolution.branchId().equals(partition.id()));
            if (allowed) {
                return new ScopeEvidence("BRANCH", resolution.branchId(),
                    resolution.branchId(), resolution.membershipVersion());
            }
        }
        throw new PricingPolicyException("INVENTORY_READ_FORBIDDEN",
            "Inventory read requires ALL or the Property-owned BRANCH partition");
    }

    public record ScopeEvidence(
        String type,
        UUID partitionId,
        UUID propertyBranchId,
        long propertyMembershipVersion
    ) {}
}
