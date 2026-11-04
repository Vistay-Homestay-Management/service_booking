package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.booking.application.AuthorizationClient.Partition;
import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PricingScopePolicyTest {
    private static final String PERMISSION = "pricing:update";
    private static final UUID PROPERTY_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();

    @Test
    void allScopeAllowsWithoutCallingPropertyAuthority() {
        var calls = new AtomicInteger();
        var policy = new PricingScopePolicy(propertyId -> {
            calls.incrementAndGet();
            return PropertyPartitionClient.Resolution.unavailable();
        });

        assertThatCode(() -> policy.require(snapshot(Set.of(Scope.ALL), Set.of()),
            PERMISSION, PROPERTY_ID)).doesNotThrowAnyException();
        assertThat(calls).hasValue(0);
    }

    @Test
    void matchingBranchPartitionAllowsPricingAccess() {
        var policy = new PricingScopePolicy(propertyId ->
            PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 7));

        assertThatCode(() -> policy.require(
            snapshot(Set.of(Scope.PARTITION), Set.of(new Partition("BRANCH", BRANCH_ID))),
            PERMISSION, PROPERTY_ID
        )).doesNotThrowAnyException();
    }

    @Test
    void partitionScopeDoesNotAllowAnotherBranchOrPartitionType() {
        var policy = new PricingScopePolicy(propertyId ->
            PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 7));

        assertCode(policy,
            snapshot(Set.of(Scope.PARTITION), Set.of(
                new Partition("BRANCH", UUID.randomUUID()),
                new Partition("CITY", BRANCH_ID)
            )), "FORBIDDEN_PRICING_SCOPE");
    }

    @Test
    void assignedOrOwnScopesCannotSubstituteForPricingPartition() {
        var calls = new AtomicInteger();
        var policy = new PricingScopePolicy(propertyId -> {
            calls.incrementAndGet();
            return PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 7);
        });

        assertCode(policy, snapshot(Set.of(Scope.ASSIGNED, Scope.OWN), Set.of()),
            "FORBIDDEN_PRICING_SCOPE");
        assertThat(calls).hasValue(0);
    }

    @Test
    void missingPropertyPartitionFailsClosed() {
        var policy = new PricingScopePolicy(propertyId ->
            PropertyPartitionClient.Resolution.missing());

        assertCode(policy,
            snapshot(Set.of(Scope.PARTITION), Set.of(new Partition("BRANCH", BRANCH_ID))),
            "FORBIDDEN_PRICING_SCOPE");
    }

    @Test
    void unavailablePropertyAuthorityHasDistinctFailureCode() {
        var policy = new PricingScopePolicy(propertyId ->
            PropertyPartitionClient.Resolution.unavailable());

        assertCode(policy,
            snapshot(Set.of(Scope.PARTITION), Set.of(new Partition("BRANCH", BRANCH_ID))),
            "PROPERTY_PARTITION_UNAVAILABLE");
    }

    private void assertCode(
        PricingScopePolicy policy,
        AuthorizationClient.Snapshot snapshot,
        String expectedCode
    ) {
        assertThatThrownBy(() -> policy.require(snapshot, PERMISSION, PROPERTY_ID))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private AuthorizationClient.Snapshot snapshot(Set<Scope> scopes, Set<Partition> partitions) {
        return new AuthorizationClient.Snapshot(
            true,
            UUID.randomUUID(),
            1,
            Map.of(PERMISSION, scopes),
            Map.of(PERMISSION, partitions)
        );
    }
}
