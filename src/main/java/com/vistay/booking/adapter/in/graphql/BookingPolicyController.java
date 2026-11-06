package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.BookingPolicyService;
import com.vistay.booking.application.BookingPolicyService.ChangeRequestView;
import com.vistay.booking.application.BookingPolicyService.PolicyChangeInput;
import com.vistay.booking.application.BookingPolicyService.PolicySimulationInput;
import com.vistay.booking.application.BookingPolicyService.PolicyView;
import com.vistay.booking.application.BookingPolicyService.SimulationView;
import com.vistay.booking.application.PricingPolicyException;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class BookingPolicyController {
    private final BookingPolicyService policies;
    private final AuthorizationContext authorization;

    public BookingPolicyController(BookingPolicyService policies, AuthorizationContext authorization) {
        this.policies = policies;
        this.authorization = authorization;
    }

    @QueryMapping
    @RequiresPermission("booking-policy:read")
    public List<PolicyView> bookingPolicyCatalog(@AuthenticationPrincipal Jwt jwt,
        @Argument boolean includeDrafts) {
        authorization.require(jwt, "booking-policy:read");
        if (includeDrafts) requireAll(jwt, "booking-policy:change-request");
        return policies.catalog(includeDrafts);
    }

    @QueryMapping
    @RequiresPermission("booking-policy:read")
    public List<ChangeRequestView> bookingPolicyChangeRequests(@AuthenticationPrincipal Jwt jwt,
        @Argument String status) {
        authorization.require(jwt, "booking-policy:read");
        return policies.changeRequests(status);
    }

    @MutationMapping
    @RequiresPermission("booking-policy:change-request")
    public ChangeRequestView requestBookingPolicyChange(@AuthenticationPrincipal Jwt jwt,
        @Argument PolicyChangeInput input) {
        requireAll(jwt, "booking-policy:change-request");
        return policies.request(actor(jwt), input);
    }

    @MutationMapping
    @RequiresPermission("booking-policy:change-approve")
    public ChangeRequestView approveBookingPolicyChange(@AuthenticationPrincipal Jwt jwt,
        @Argument UUID id, @Argument String reason) {
        requireAll(jwt, "booking-policy:change-approve");
        return policies.decide(actor(jwt), id, true, reason);
    }

    @MutationMapping
    @RequiresPermission("booking-policy:change-approve")
    public ChangeRequestView rejectBookingPolicyChange(@AuthenticationPrincipal Jwt jwt,
        @Argument UUID id, @Argument String reason) {
        requireAll(jwt, "booking-policy:change-approve");
        return policies.decide(actor(jwt), id, false, reason);
    }

    @MutationMapping
    @RequiresPermission("booking-policy:simulate")
    public SimulationView simulateBookingPolicy(@AuthenticationPrincipal Jwt jwt,
        @Argument PolicySimulationInput input) {
        authorization.require(jwt, "booking-policy:simulate");
        return policies.simulate(actor(jwt), input);
    }

    private static UUID actor(Jwt jwt) {
        if (jwt == null) throw new PricingPolicyException("UNAUTHENTICATED", "Authentication is required");
        try { return UUID.fromString(jwt.getSubject()); }
        catch (RuntimeException invalid) { throw new PricingPolicyException("INVALID_TOKEN", "JWT subject is invalid"); }
    }

    private void requireAll(Jwt jwt, String permission) {
        if (!authorization.require(jwt, permission).contains("ALL")) {
            throw new PricingPolicyException("FORBIDDEN_BOOKING_POLICY_SCOPE",
                permission + " requires ALL scope for policy governance");
        }
    }
}
