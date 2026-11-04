package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.booking.application.AuthorizationClient.Partition;
import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class InventoryScopePolicyTest {
    private static final String PERMISSION = "inventory:read";
    private static final String BLOCK_PERMISSION = "inventory:block";
    private static final UUID PROPERTY_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();

    @Test
    void allStillResolvesAuthoritativePropertyPartition() {
        var calls = new AtomicInteger();
        var policy = new InventoryScopePolicy(propertyId -> {
            calls.incrementAndGet();
            assertThat(propertyId).isEqualTo(PROPERTY_ID);
            return PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 17);
        });

        var evidence = policy.require(snapshot(Set.of(Scope.ALL), Set.of()),
            PERMISSION, PROPERTY_ID);

        assertThat(calls).hasValue(1);
        assertThat(evidence.type()).isEqualTo("ALL");
        assertThat(evidence.partitionId()).isNull();
        assertThat(evidence.propertyBranchId()).isEqualTo(BRANCH_ID);
        assertThat(evidence.propertyMembershipVersion()).isEqualTo(17);
    }

    @Test
    void matchingBranchPartitionAllowsAndRetainsPropertyEvidence() {
        var policy = mappedPolicy();

        var evidence = policy.require(snapshot(Set.of(Scope.PARTITION),
            Set.of(new Partition("BRANCH", BRANCH_ID))), PERMISSION, PROPERTY_ID);

        assertThat(evidence.type()).isEqualTo("BRANCH");
        assertThat(evidence.partitionId()).isEqualTo(BRANCH_ID);
        assertThat(evidence.propertyMembershipVersion()).isEqualTo(17);
    }

    @Test
    void foreignBranchAndUnsupportedScopesFailClosed() {
        var policy = mappedPolicy();

        assertCode(policy, snapshot(Set.of(Scope.PARTITION), Set.of(
            new Partition("BRANCH", UUID.randomUUID()),
            new Partition("AREA", BRANCH_ID))), "INVENTORY_READ_FORBIDDEN");
        assertCode(policy, snapshot(Set.of(Scope.SELF, Scope.OWN, Scope.ASSIGNED), Set.of()),
            "INVENTORY_READ_FORBIDDEN");
    }

    @Test
    void roleMaximumPreventsAllFromElevatingBranchBoundRole() {
        var policy = mappedPolicy();
        var branchRoleWithAll = new AuthorizationClient.Snapshot(true, UUID.randomUUID(), 9,
            Map.of(PERMISSION, Set.of(Scope.ALL)), Map.of(PERMISSION, Set.of()),
            Set.of("MANAGER"));
        var globalRoleWithPartition = new AuthorizationClient.Snapshot(true, UUID.randomUUID(), 9,
            Map.of(PERMISSION, Set.of(Scope.PARTITION)),
            Map.of(PERMISSION, Set.of(new Partition("BRANCH", BRANCH_ID))),
            Set.of("SUPER_ADMIN"));

        assertCode(policy, branchRoleWithAll, "INVENTORY_READ_FORBIDDEN");
        assertCode(policy, globalRoleWithPartition, "INVENTORY_READ_FORBIDDEN");
    }

    @Test
    void supportedGlobalAndBranchRolesFollowTheirDeclaredMaximumScope() {
        var policy = mappedPolicy();
        for (var role : Set.of("SUPER_ADMIN", "CTV")) {
            var snapshot = new AuthorizationClient.Snapshot(true, UUID.randomUUID(), 9,
                Map.of(PERMISSION, Set.of(Scope.ALL)), Map.of(PERMISSION, Set.of()),
                Set.of(role));
            assertThat(policy.require(snapshot, PERMISSION, PROPERTY_ID).type())
                .as(role).isEqualTo("ALL");
        }
        for (var role : Set.of("MANAGER", "RECEPTIONIST", "STAFF")) {
            var snapshot = new AuthorizationClient.Snapshot(true, UUID.randomUUID(), 9,
                Map.of(PERMISSION, Set.of(Scope.PARTITION)),
                Map.of(PERMISSION, Set.of(new Partition("BRANCH", BRANCH_ID))),
                Set.of(role));
            assertThat(policy.require(snapshot, PERMISSION, PROPERTY_ID).type())
                .as(role).isEqualTo("BRANCH");
        }
    }

    @Test
    void blockCommandsAllowOnlySuperAdminAllOrManagerCurrentBranch() {
        var policy = mappedPolicy();
        var superAdmin = blockSnapshot("SUPER_ADMIN", Set.of(Scope.ALL), Set.of());
        var manager = blockSnapshot("MANAGER", Set.of(Scope.PARTITION),
            Set.of(new Partition("BRANCH", BRANCH_ID)));

        assertThat(policy.requireWrite(superAdmin, BLOCK_PERMISSION, PROPERTY_ID).type())
            .isEqualTo("ALL");
        assertThat(policy.requireWrite(manager, BLOCK_PERMISSION, PROPERTY_ID).type())
            .isEqualTo("BRANCH");

        for (var role : Set.of("CTV", "RECEPTIONIST", "STAFF")) {
            var scopes = "CTV".equals(role) ? Set.of(Scope.ALL) : Set.of(Scope.PARTITION);
            var partitions = "CTV".equals(role) ? Set.<Partition>of()
                : Set.of(new Partition("BRANCH", BRANCH_ID));
            assertWriteCode(policy, blockSnapshot(role, scopes, partitions),
                "INVENTORY_BLOCK_FORBIDDEN");
        }
        assertWriteCode(policy,
            blockSnapshot("MANAGER", Set.of(Scope.ALL), Set.of()),
            "INVENTORY_BLOCK_FORBIDDEN");
        assertWriteCode(policy,
            blockSnapshot("SUPER_ADMIN", Set.of(Scope.PARTITION),
                Set.of(new Partition("BRANCH", BRANCH_ID))),
            "INVENTORY_BLOCK_FORBIDDEN");
        assertWriteCode(policy,
            blockSnapshot("MANAGER", Set.of(Scope.PARTITION),
                Set.of(new Partition("BRANCH", UUID.randomUUID()))),
            "INVENTORY_BLOCK_FORBIDDEN");
    }

    @Test
    void notFoundUnmappedOrUnavailablePropertyFailsEvenForAll() {
        assertCode(new InventoryScopePolicy(propertyId ->
                PropertyPartitionClient.Resolution.notFound()),
            snapshot(Set.of(Scope.ALL), Set.of()), "PROPERTY_NOT_FOUND");
        assertCode(new InventoryScopePolicy(propertyId ->
                PropertyPartitionClient.Resolution.unmapped()),
            snapshot(Set.of(Scope.ALL), Set.of()), "PROPERTY_PARTITION_UNMAPPED");
        assertCode(new InventoryScopePolicy(propertyId ->
                PropertyPartitionClient.Resolution.unavailable()),
            snapshot(Set.of(Scope.ALL), Set.of()), "PROPERTY_PARTITION_UNAVAILABLE");
        assertCode(new InventoryScopePolicy(propertyId ->
                PropertyPartitionClient.Resolution.mapped(null, 1)),
            snapshot(Set.of(Scope.ALL), Set.of()), "PROPERTY_PARTITION_UNAVAILABLE");
    }

    private InventoryScopePolicy mappedPolicy() {
        return new InventoryScopePolicy(propertyId ->
            PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 17));
    }

    private void assertCode(
        InventoryScopePolicy policy,
        AuthorizationClient.Snapshot snapshot,
        String expectedCode
    ) {
        assertThatThrownBy(() -> policy.require(snapshot, PERMISSION, PROPERTY_ID))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private AuthorizationClient.Snapshot snapshot(Set<Scope> scopes, Set<Partition> partitions) {
        return new AuthorizationClient.Snapshot(true, UUID.randomUUID(), 9,
            Map.of(PERMISSION, scopes), Map.of(PERMISSION, partitions),
            scopes.contains(Scope.ALL) ? Set.of("SUPER_ADMIN") : Set.of("MANAGER"));
    }

    private AuthorizationClient.Snapshot blockSnapshot(
        String role,
        Set<Scope> scopes,
        Set<Partition> partitions
    ) {
        return new AuthorizationClient.Snapshot(true, UUID.randomUUID(), 9,
            Map.of(BLOCK_PERMISSION, scopes), Map.of(BLOCK_PERMISSION, partitions),
            Set.of(role));
    }

    private void assertWriteCode(
        InventoryScopePolicy policy,
        AuthorizationClient.Snapshot snapshot,
        String expectedCode
    ) {
        assertThatThrownBy(() -> policy.requireWrite(
                snapshot, BLOCK_PERMISSION, PROPERTY_ID))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }
}
