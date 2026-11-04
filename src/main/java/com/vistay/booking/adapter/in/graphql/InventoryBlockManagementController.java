package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.InventoryBlockManagementService;
import com.vistay.booking.application.InventoryBlockManagementService.Query;
import com.vistay.booking.application.InventoryScopePolicy;
import com.vistay.booking.application.PricingPolicyException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class InventoryBlockManagementController {
    private static final String READ = "inventory:read";

    private final InventoryBlockManagementService inventory;
    private final AuthorizationClient authorizations;
    private final InventoryScopePolicy scopes;

    public InventoryBlockManagementController(
        InventoryBlockManagementService inventory,
        AuthorizationClient authorizations,
        InventoryScopePolicy scopes
    ) {
        this.inventory = inventory;
        this.authorizations = authorizations;
        this.scopes = scopes;
    }

    @QueryMapping
    public InventoryBlockManagementPage inventoryBlocks(
        @AuthenticationPrincipal Jwt jwt,
        @Argument InventoryBlockManagementInput input
    ) {
        var accountId = accountId(jwt);
        var query = query(input);
        var authorization = authorizations.require(accountId, READ,
            "INVENTORY_READ_FORBIDDEN");
        var scope = scopes.require(authorization, READ, query.propertyId());
        var page = inventory.list(query);
        return new InventoryBlockManagementPage(
            page.nodes(), page.pageInfo(), Long.toString(page.inventoryRevision()),
            scope.propertyBranchId().toString(),
            Long.toString(scope.propertyMembershipVersion()));
    }

    private static Query query(InventoryBlockManagementInput input) {
        try {
            if (input == null) throw invalidInput();
            var propertyId = UUID.fromString(input.propertyId());
            if (!propertyId.toString().equals(input.propertyId())) throw invalidInput();
            var fromDate = parseDate(input.fromDate());
            var toDate = parseDate(input.toDateExclusive());
            var types = input.types() == null ? List.<String>of()
                : input.types().stream().map(Enum::name).toList();
            var statuses = input.statuses() == null ? List.<String>of()
                : input.statuses().stream().map(Enum::name).toList();
            int first = input.first() == null ? 50 : input.first();
            if (first < 1 || first > 100 || (fromDate != null && toDate != null
                && !toDate.isAfter(fromDate))) {
                throw invalidInput();
            }
            return new Query(propertyId, fromDate, toDate, types, statuses,
                first, input.after());
        } catch (DateTimeException | IllegalArgumentException | NullPointerException invalid) {
            throw invalidInput();
        }
    }

    private static LocalDate parseDate(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

    private static UUID accountId(Jwt jwt) {
        if (jwt == null) throw unauthenticated();
        try {
            var value = jwt.getSubject();
            if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
                throw unauthenticated();
            }
            var id = UUID.fromString(value);
            if (!id.toString().equals(value)) throw unauthenticated();
            return id;
        } catch (IllegalArgumentException invalid) {
            throw unauthenticated();
        }
    }

    private static PricingPolicyException invalidInput() {
        return new PricingPolicyException("INVALID_INVENTORY_MANAGEMENT_INPUT",
            "Inventory management filters are invalid");
    }

    private static PricingPolicyException unauthenticated() {
        return new PricingPolicyException("UNAUTHENTICATED", "Authentication is required");
    }

    public record InventoryBlockManagementInput(
        String propertyId,
        String fromDate,
        String toDateExclusive,
        List<InventoryBlockType> types,
        List<InventoryBlockStatus> statuses,
        Integer first,
        String after
    ) {}

    public record InventoryBlockManagementPage(
        List<InventoryBlockManagementService.Block> nodes,
        InventoryBlockManagementService.PageInfo pageInfo,
        String inventoryRevision,
        String branchId,
        String propertyMembershipVersion
    ) {}

    public enum InventoryBlockType {
        BOOKING, MAINTENANCE, CLEANING, OWNER_USE, MANUAL, CHANNEL
    }

    public enum InventoryBlockStatus {
        TENTATIVE, ACTIVE, RELEASED, CANCELLED
    }
}
