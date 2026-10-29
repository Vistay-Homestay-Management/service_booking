package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.InventoryAvailabilityService;
import com.vistay.booking.application.InventoryAvailabilityService.Availability;
import com.vistay.booking.application.InventoryScopePolicy;
import com.vistay.booking.application.PricingPolicyException;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class InventoryAvailabilityController {
    private static final String READ = "inventory:read";

    private final InventoryAvailabilityService availability;
    private final AuthorizationClient authorizations;
    private final InventoryScopePolicy scopes;

    public InventoryAvailabilityController(
        InventoryAvailabilityService availability,
        AuthorizationClient authorizations,
        InventoryScopePolicy scopes
    ) {
        this.availability = availability;
        this.authorizations = authorizations;
        this.scopes = scopes;
    }

    @QueryMapping
    public Availability propertyAvailability(
        @AuthenticationPrincipal Jwt jwt,
        @Argument PropertyAvailabilityInput input
    ) {
        var accountId = accountId(jwt);
        final UUID propertyId;
        final LocalDate checkInDate;
        final LocalDate checkOutDate;
        try {
            propertyId = UUID.fromString(input.propertyId());
            checkInDate = LocalDate.parse(input.checkInDate());
            checkOutDate = LocalDate.parse(input.checkOutDate());
        } catch (DateTimeException | IllegalArgumentException | NullPointerException invalid) {
            throw new PricingPolicyException("INVALID_INVENTORY_RANGE",
                "Property ID and stay dates must use the inventory-v1 format");
        }
        if (!checkOutDate.isAfter(checkInDate)) {
            throw new PricingPolicyException("INVALID_INVENTORY_RANGE",
                "Checkout must be after check-in for a half-open date range");
        }
        var authorization = authorizations.require(
            accountId, READ, "INVENTORY_READ_FORBIDDEN");
        var scope = scopes.require(authorization, READ, propertyId);
        return availability.observe(propertyId, checkInDate, checkOutDate, scope);
    }

    private static UUID accountId(Jwt jwt) {
        if (jwt == null) {
            throw new PricingPolicyException("UNAUTHENTICATED", "Authentication is required");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new PricingPolicyException("UNAUTHENTICATED", "Authentication is required");
        }
    }

    public record PropertyAvailabilityInput(
        String propertyId,
        String checkInDate,
        String checkOutDate
    ) {}
}
