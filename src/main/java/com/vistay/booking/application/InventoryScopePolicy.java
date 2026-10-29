package com.vistay.booking.application;

import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class InventoryScopePolicy {
    private static final java.util.Set<String> READ_GLOBAL_ROLES =
        java.util.Set.of("SUPER_ADMIN", "CTV");
    private static final java.util.Set<String> READ_BRANCH_ROLES =
        java.util.Set.of("MANAGER", "RECEPTIONIST", "STAFF");
    private static final java.util.Set<String> WRITE_GLOBAL_ROLES =
        java.util.Set.of("SUPER_ADMIN");
    private static final java.util.Set<String> WRITE_BRANCH_ROLES =
        java.util.Set.of("MANAGER");
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
        return require(snapshot, permission, propertyId, READ_GLOBAL_ROLES,
            READ_BRANCH_ROLES, "INVENTORY_READ_FORBIDDEN",
            "Inventory read requires ALL or the Property-owned BRANCH partition");
    }

    public ScopeEvidence requireWrite(
        AuthorizationClient.Snapshot snapshot,
        String permission,
        UUID propertyId
    ) {
        return require(snapshot, permission, propertyId, WRITE_GLOBAL_ROLES,
            WRITE_BRANCH_ROLES, "INVENTORY_BLOCK_FORBIDDEN",
            "Inventory block command requires ALL or the Property-owned BRANCH partition");
    }

    private ScopeEvidence require(
        AuthorizationClient.Snapshot snapshot,
        String permission,
        UUID propertyId,
        java.util.Set<String> globalRoles,
        java.util.Set<String> branchRoles,
        String forbiddenCode,
        String forbiddenMessage
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
        var branchBound = snapshot.roles().stream().anyMatch(branchRoles::contains);
        var global = snapshot.roles().stream().anyMatch(globalRoles::contains);
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
        throw new PricingPolicyException(forbiddenCode, forbiddenMessage);
    }

    public record ScopeEvidence(
        String type,
        UUID partitionId,
        UUID propertyBranchId,
        long propertyMembershipVersion
    ) {}
}
