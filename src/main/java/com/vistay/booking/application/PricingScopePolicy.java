package com.vistay.booking.application;

import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PricingScopePolicy {
    private final PropertyPartitionClient properties;

    public PricingScopePolicy(PropertyPartitionClient properties) {
        this.properties = properties;
    }

    public void require(AuthorizationClient.Snapshot snapshot, String permission, UUID propertyId) {
        var scopes = snapshot.scopes(permission);
        if (scopes.contains(Scope.ALL)) return;
        if (!scopes.contains(Scope.PARTITION)) {
            throw new PricingPolicyException("FORBIDDEN_PRICING_SCOPE",
                "Pricing action requires ALL or an authorized BRANCH partition");
        }
        var resolution = properties.resolve(propertyId);
        if (resolution.status() == PropertyPartitionClient.Status.UNAVAILABLE) {
            throw new PricingPolicyException("PROPERTY_PARTITION_UNAVAILABLE",
                "Property partition authority is unavailable");
        }
        if (resolution.status() != PropertyPartitionClient.Status.MAPPED) {
            throw new PricingPolicyException("FORBIDDEN_PRICING_SCOPE",
                "Property has no active branch partition");
        }
        var allowed = snapshot.partitions(permission).stream().anyMatch(partition ->
            "BRANCH".equals(partition.type()) && resolution.branchId().equals(partition.id()));
        if (!allowed) {
            throw new PricingPolicyException("FORBIDDEN_PRICING_SCOPE",
                "Property is outside the authorized branch partition");
        }
    }
}
