package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingRecordScopePolicyTest {
    private final BookingRecordScopePolicy policy = new BookingRecordScopePolicy();
    private final UUID actorProfileId = UUID.randomUUID();

    @Test
    void ownNeverGrantsAnAdministratorBookingAccess() {
        assertThat(policy.allows(Set.of(Scope.OWN), actorProfileId, null, false)).isFalse();
    }

    @Test
    void selfNeverGrantsAnAdministratorBookingAccess() {
        assertThat(policy.allows(Set.of(Scope.SELF), actorProfileId, actorProfileId, true)).isFalse();
    }

    @Test
    void assignedRequiresAnAuthoritativeDirectOrWorkflowAssignment() {
        assertThat(policy.allows(Set.of(Scope.ASSIGNED), actorProfileId, actorProfileId, false)).isTrue();
        assertThat(policy.allows(Set.of(Scope.ASSIGNED), actorProfileId, null, true)).isTrue();
        assertThat(policy.allows(Set.of(Scope.ASSIGNED), actorProfileId, null, false)).isFalse();
    }

    @Test
    void allDominatesOtherScopes() {
        assertThat(policy.allows(Set.of(Scope.ALL), null, null, false)).isTrue();
    }
}
