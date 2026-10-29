package com.vistay.booking.application;

import com.vistay.booking.application.AuthorizationClient.Scope;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Evaluates record access using authoritative booking relationships.
 *
 * <p>For an administrator, creating a booking is audit provenance and is not business ownership.
 * Consequently OWN and SELF do not grant access here. A booking is visible only platform-wide or
 * through an active direct/workflow assignment supplied by the Booking service.</p>
 */
@Component
public class BookingRecordScopePolicy {

    public boolean allows(
        Set<Scope> scopes,
        UUID actorProfileId,
        UUID assignedAdminUserId,
        boolean attributedToActor
    ) {
        if (scopes.contains(Scope.ALL)) {
            return true;
        }
        if (!scopes.contains(Scope.ASSIGNED) || actorProfileId == null) {
            return false;
        }
        return actorProfileId.equals(assignedAdminUserId) || attributedToActor;
    }
}
