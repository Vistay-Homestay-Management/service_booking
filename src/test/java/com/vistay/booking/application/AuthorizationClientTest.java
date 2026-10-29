package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.contracts.common.v1.DataScopeType;
import com.vistay.contracts.common.v1.PartitionGrant;
import com.vistay.contracts.common.v1.PermissionGrant;
import com.vistay.contracts.user.v1.GetAuthorizationSnapshotResponse;
import graphql.GraphqlErrorException;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class AuthorizationClientTest {
    private static final UUID PROFILE_ID = UUID.randomUUID();
    private static final UUID BRANCH_ID = UUID.randomUUID();

    @Test
    void decodesRolesScopesAndPartitionsFromTrustworthySnapshot() {
        var snapshot = AuthorizationClient.decode(valid().build());

        assertThat(snapshot.active()).isTrue();
        assertThat(snapshot.profileId()).isEqualTo(PROFILE_ID);
        assertThat(snapshot.authorizationVersion()).isEqualTo(7);
        assertThat(snapshot.roles()).containsExactly("MANAGER");
        assertThat(snapshot.scopes("inventory:read"))
            .containsExactly(AuthorizationClient.Scope.PARTITION);
        assertThat(snapshot.partitions("inventory:read"))
            .containsExactly(new AuthorizationClient.Partition("BRANCH", BRANCH_ID));
    }

    @Test
    void malformedProfileVersionGrantAndPartitionFailWithStableDependencyCode() {
        assertUnavailable(builder -> builder.clearProfileId());
        assertUnavailable(builder -> builder.setProfileId("not-a-uuid"));
        assertUnavailable(builder -> builder.setAuthorizationVersion(-1));
        assertUnavailable(builder -> builder.clearGrants().addGrants(
            grant(DataScopeType.DATA_SCOPE_TYPE_UNSPECIFIED, BRANCH_ID.toString())));
        assertUnavailable(builder -> builder.clearGrants().addGrants(
            grant(DataScopeType.DATA_SCOPE_TYPE_PARTITION, "not-a-uuid")));
        assertUnavailable(builder -> builder.clearPermissions());
    }

    @Test
    void inactiveSnapshotMayOmitProfileAndIsDeniedByInventorySpecificRequirePath() {
        var snapshot = AuthorizationClient.decode(GetAuthorizationSnapshotResponse.newBuilder()
            .setActive(false)
            .setAuthorizationVersion(8)
            .build());

        assertThat(snapshot.active()).isFalse();
        assertThat(snapshot.profileId()).isNull();
        assertForbidden(snapshot);
    }

    @Test
    void missingInventoryPermissionUsesInventorySpecificForbiddenCode() {
        var snapshot = new AuthorizationClient.Snapshot(true, PROFILE_ID, 8,
            Map.of(), Map.of(), Set.of("MANAGER"));

        assertForbidden(snapshot);
    }

    private void assertForbidden(AuthorizationClient.Snapshot snapshot) {
        assertThatThrownBy(() -> AuthorizationClient.enforce(
                snapshot, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .isInstanceOfSatisfying(GraphqlErrorException.class, error ->
                assertThat(error.getExtensions()).containsEntry(
                    "code", "INVENTORY_READ_FORBIDDEN"));
    }

    private void assertUnavailable(
        UnaryOperator<GetAuthorizationSnapshotResponse.Builder> mutation
    ) {
        assertThatThrownBy(() -> AuthorizationClient.decode(mutation.apply(valid()).build()))
            .isInstanceOfSatisfying(GraphqlErrorException.class, error ->
                assertThat(error.getExtensions()).containsEntry(
                    "code", "AUTHORIZATION_UNAVAILABLE"));
    }

    private GetAuthorizationSnapshotResponse.Builder valid() {
        return GetAuthorizationSnapshotResponse.newBuilder()
            .setActive(true)
            .setProfileId(PROFILE_ID.toString())
            .setAuthorizationVersion(7)
            .addRoles("MANAGER")
            .addPermissions("inventory:read")
            .addGrants(grant(DataScopeType.DATA_SCOPE_TYPE_PARTITION,
                BRANCH_ID.toString()));
    }

    private PermissionGrant grant(DataScopeType scope, String partitionId) {
        return PermissionGrant.newBuilder()
            .setPermissionCode("inventory:read")
            .addScopes(scope)
            .addPartitions(PartitionGrant.newBuilder()
                .setPartitionType("BRANCH")
                .setPartitionId(partitionId)
                .build())
            .build();
    }
}
