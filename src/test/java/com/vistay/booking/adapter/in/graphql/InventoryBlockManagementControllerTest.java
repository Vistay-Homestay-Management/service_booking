package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.booking.adapter.in.graphql.InventoryBlockManagementController.InventoryBlockManagementInput;
import com.vistay.booking.adapter.in.graphql.InventoryBlockManagementController.InventoryBlockStatus;
import com.vistay.booking.adapter.in.graphql.InventoryBlockManagementController.InventoryBlockType;
import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Partition;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.InventoryBlockManagementService;
import com.vistay.booking.application.InventoryBlockManagementService.Page;
import com.vistay.booking.application.InventoryBlockManagementService.PageInfo;
import com.vistay.booking.application.InventoryScopePolicy;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PropertyPartitionClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

class InventoryBlockManagementControllerTest {
    private static final UUID ACCOUNT_ID =
        UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PROFILE_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID PROPERTY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID BRANCH_ID =
        UUID.fromString("50000000-0000-0000-0000-000000000005");

    private InventoryBlockManagementService inventory;
    private AuthorizationClient authorizations;

    @BeforeEach
    void setUp() {
        inventory = mock(InventoryBlockManagementService.class);
        authorizations = mock(AuthorizationClient.class);
    }

    @Test
    void resolvesReadScopeAndReturnsRevisionPinnedPage() {
        when(authorizations.require(ACCOUNT_ID, "inventory:read",
            "INVENTORY_READ_FORBIDDEN")).thenReturn(managerSnapshot());
        when(inventory.list(any())).thenReturn(new Page(List.of(),
            new PageInfo(false, null), 18));
        var controller = controller(PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 7));

        var result = controller.inventoryBlocks(jwt(), new InventoryBlockManagementInput(
            PROPERTY_ID.toString(), "2027-01-01", "2027-02-01",
            List.of(InventoryBlockType.MAINTENANCE, InventoryBlockType.CLEANING),
            List.of(InventoryBlockStatus.ACTIVE), 25, null));

        var query = ArgumentCaptor.forClass(InventoryBlockManagementService.Query.class);
        verify(inventory).list(query.capture());
        assertThat(query.getValue().propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(query.getValue().fromDate()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(query.getValue().toDateExclusive()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(query.getValue().types()).containsExactly("MAINTENANCE", "CLEANING");
        assertThat(query.getValue().statuses()).containsExactly("ACTIVE");
        assertThat(query.getValue().first()).isEqualTo(25);
        assertThat(result.inventoryRevision()).isEqualTo("18");
        assertThat(result.branchId()).isEqualTo(BRANCH_ID.toString());
        assertThat(result.propertyMembershipVersion()).isEqualTo("7");
    }

    @Test
    void defaultsPageSizeAndRejectsMalformedInputBeforeAuthorization() {
        var valid = new InventoryBlockManagementInput(PROPERTY_ID.toString(),
            null, null, null, null, null, null);
        when(authorizations.require(ACCOUNT_ID, "inventory:read",
            "INVENTORY_READ_FORBIDDEN")).thenReturn(superSnapshot());
        when(inventory.list(any())).thenReturn(new Page(List.of(),
            new PageInfo(false, null), 0));
        var controller = controller(PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 1));
        controller.inventoryBlocks(jwt(), valid);
        var query = ArgumentCaptor.forClass(InventoryBlockManagementService.Query.class);
        verify(inventory).list(query.capture());
        assertThat(query.getValue().first()).isEqualTo(50);
        assertThat(query.getValue().types()).isEmpty();
        assertThat(query.getValue().statuses()).isEmpty();

        assertCode(() -> controller.inventoryBlocks(jwt(),
            new InventoryBlockManagementInput(PROPERTY_ID.toString(),
                "2027-02-01", "2027-01-01", List.of(), List.of(), 50, null)),
            "INVALID_INVENTORY_MANAGEMENT_INPUT");
        assertCode(() -> controller.inventoryBlocks(null, valid), "UNAUTHENTICATED");
        verify(inventory, never()).list(new InventoryBlockManagementService.Query(
            PROPERTY_ID, LocalDate.of(2027, 2, 1), LocalDate.of(2027, 1, 1),
            List.of(), List.of(), 50, null));
    }

    @Test
    void foreignBranchFailsClosedWithoutQueryingInventory() {
        when(authorizations.require(ACCOUNT_ID, "inventory:read",
            "INVENTORY_READ_FORBIDDEN")).thenReturn(managerSnapshot());
        var controller = controller(PropertyPartitionClient.Resolution.mapped(
            UUID.fromString("60000000-0000-0000-0000-000000000006"), 4));

        assertCode(() -> controller.inventoryBlocks(jwt(),
            new InventoryBlockManagementInput(PROPERTY_ID.toString(), null, null,
                List.of(), List.of(), 20, null)), "INVENTORY_READ_FORBIDDEN");
        verify(inventory, never()).list(any());
    }

    private InventoryBlockManagementController controller(
        PropertyPartitionClient.Resolution resolution
    ) {
        return new InventoryBlockManagementController(inventory, authorizations,
            new InventoryScopePolicy(propertyId -> resolution));
    }

    private AuthorizationClient.Snapshot managerSnapshot() {
        return new AuthorizationClient.Snapshot(true, PROFILE_ID, 9,
            Map.of("inventory:read", Set.of(Scope.PARTITION)),
            Map.of("inventory:read", Set.of(new Partition("BRANCH", BRANCH_ID))),
            Set.of("MANAGER"));
    }

    private AuthorizationClient.Snapshot superSnapshot() {
        return new AuthorizationClient.Snapshot(true, PROFILE_ID, 9,
            Map.of("inventory:read", Set.of(Scope.ALL)),
            Map.of("inventory:read", Set.of()), Set.of("SUPER_ADMIN"));
    }

    private Jwt jwt() {
        return Jwt.withTokenValue("test-token").header("alg", "none")
            .subject(ACCOUNT_ID.toString()).build();
    }

    private void assertCode(
        org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
        String expectedCode
    ) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PricingPolicyException.class,
            error -> assertThat(error.code()).isEqualTo(expectedCode));
    }
}
