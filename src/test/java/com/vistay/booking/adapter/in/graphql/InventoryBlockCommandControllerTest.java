package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.booking.adapter.in.graphql.InventoryBlockCommandController.CreateInventoryBlockInput;
import com.vistay.booking.adapter.in.graphql.InventoryBlockCommandController.OperatorInventoryBlockType;
import com.vistay.booking.adapter.in.graphql.InventoryBlockCommandController.ReleaseInventoryBlockInput;
import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Partition;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.InventoryBlockCommandService;
import com.vistay.booking.application.InventoryScopePolicy;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PropertyPartitionClient;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

class InventoryBlockCommandControllerTest {
    private static final UUID ACCOUNT_ID =
        UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PROFILE_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID PROPERTY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID BRANCH_ID =
        UUID.fromString("50000000-0000-0000-0000-000000000005");

    private InventoryBlockCommandService commands;
    private AuthorizationClient authorizations;

    @BeforeEach
    void setUp() {
        commands = mock(InventoryBlockCommandService.class);
        authorizations = mock(AuthorizationClient.class);
    }

    @Test
    void createUsesBlockPermissionAndResolvedBranchEvidence() {
        when(authorizations.require(ACCOUNT_ID, "inventory:block",
            "INVENTORY_BLOCK_FORBIDDEN")).thenReturn(managerSnapshot());
        var controller = controller(PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 37));

        controller.createInventoryBlock(jwt(ACCOUNT_ID.toString()), new CreateInventoryBlockInput(
            "inventory.create:001", PROPERTY_ID.toString(),
            OperatorInventoryBlockType.MAINTENANCE, "2026-11-01", "2026-11-03",
            "12", "Planned maintenance window", UUID.randomUUID().toString()));

        var draft = ArgumentCaptor.forClass(InventoryBlockCommandService.CreateDraft.class);
        var evidence = ArgumentCaptor.forClass(
            InventoryBlockCommandService.AuthorizationEvidence.class);
        verify(commands).create(draft.capture(), evidence.capture());
        assertThat(draft.getValue().propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(draft.getValue().blockType()).isEqualTo("MAINTENANCE");
        assertThat(draft.getValue().startDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(draft.getValue().expectedInventoryRevision()).isEqualTo(12);
        assertThat(evidence.getValue().scope().type()).isEqualTo("BRANCH");
        assertThat(evidence.getValue().scope().propertyMembershipVersion()).isEqualTo(37);
    }

    @Test
    void releaseUsesAssertedPropertyAndExactBlockIdentity() {
        when(authorizations.require(ACCOUNT_ID, "inventory:block",
            "INVENTORY_BLOCK_FORBIDDEN")).thenReturn(superSnapshot());
        var blockId = UUID.randomUUID();
        var controller = controller(PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 8));

        controller.releaseInventoryBlock(jwt(ACCOUNT_ID.toString()),
            new ReleaseInventoryBlockInput("inventory.release:001",
                PROPERTY_ID.toString(), blockId.toString(), "4",
                "Release completed maintenance", UUID.randomUUID().toString()));

        var draft = ArgumentCaptor.forClass(InventoryBlockCommandService.ReleaseDraft.class);
        verify(commands).release(draft.capture(), any());
        assertThat(draft.getValue().propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(draft.getValue().inventoryBlockId()).isEqualTo(blockId);
        assertThat(draft.getValue().expectedInventoryRevision()).isEqualTo(4);
    }

    @Test
    void malformedInputAndDisallowedRoleFailBeforeCommand() {
        when(authorizations.require(ACCOUNT_ID, "inventory:block",
            "INVENTORY_BLOCK_FORBIDDEN")).thenReturn(managerSnapshot());
        var controller = controller(PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 1));

        assertCode(() -> controller.createInventoryBlock(jwt(ACCOUNT_ID.toString()),
            new CreateInventoryBlockInput("inventory.create:002", PROPERTY_ID.toString(),
                OperatorInventoryBlockType.MANUAL, "2026-11-03", "2026-11-03", "0",
                "Manual operator block reason", UUID.randomUUID().toString())),
            "INVALID_INVENTORY_RANGE");
        assertCode(() -> controller.releaseInventoryBlock(jwt(ACCOUNT_ID.toString()),
            new ReleaseInventoryBlockInput("inventory.release:002", "not-a-uuid",
                UUID.randomUUID().toString(), "0", "Release operator block reason",
                UUID.randomUUID().toString())), "INVALID_INVENTORY_BLOCK_INPUT");
        verify(commands, never()).create(any(), any());
        verify(commands, never()).release(any(), any());

        var receptionist = new AuthorizationClient.Snapshot(true, PROFILE_ID, 3,
            Map.of("inventory:block", Set.of(Scope.PARTITION)),
            Map.of("inventory:block", Set.of(new Partition("BRANCH", BRANCH_ID))),
            Set.of("RECEPTIONIST"));
        when(authorizations.require(ACCOUNT_ID, "inventory:block",
            "INVENTORY_BLOCK_FORBIDDEN")).thenReturn(receptionist);
        assertCode(() -> controller.createInventoryBlock(jwt(ACCOUNT_ID.toString()),
            validCreate()), "INVENTORY_BLOCK_FORBIDDEN");
        verify(commands, never()).create(any(), any());
    }

    @Test
    void authenticationAndPropertyFailuresAreSanitized() {
        assertCode(() -> controller(PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 1))
            .createInventoryBlock(null, validCreate()), "UNAUTHENTICATED");
        when(authorizations.require(eq(ACCOUNT_ID), eq("inventory:block"),
            eq("INVENTORY_BLOCK_FORBIDDEN"))).thenReturn(superSnapshot());
        assertCode(() -> controller(PropertyPartitionClient.Resolution.unmapped())
            .createInventoryBlock(jwt(ACCOUNT_ID.toString()), validCreate()),
            "PROPERTY_PARTITION_UNMAPPED");
        verify(commands, never()).create(any(), any());
    }

    private InventoryBlockCommandController controller(
        PropertyPartitionClient.Resolution resolution
    ) {
        return new InventoryBlockCommandController(commands, authorizations,
            new InventoryScopePolicy(propertyId -> resolution));
    }

    private AuthorizationClient.Snapshot managerSnapshot() {
        return new AuthorizationClient.Snapshot(true, PROFILE_ID, 19,
            Map.of("inventory:block", Set.of(Scope.PARTITION)),
            Map.of("inventory:block", Set.of(new Partition("BRANCH", BRANCH_ID))),
            Set.of("MANAGER"));
    }

    private AuthorizationClient.Snapshot superSnapshot() {
        return new AuthorizationClient.Snapshot(true, PROFILE_ID, 19,
            Map.of("inventory:block", Set.of(Scope.ALL)),
            Map.of("inventory:block", Set.of()), Set.of("SUPER_ADMIN"));
    }

    private CreateInventoryBlockInput validCreate() {
        return new CreateInventoryBlockInput("inventory.create:003",
            PROPERTY_ID.toString(), OperatorInventoryBlockType.MANUAL,
            "2026-11-01", "2026-11-02", "0",
            "Manual inventory block reason", UUID.randomUUID().toString());
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token").header("alg", "none")
            .subject(subject).build();
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
        String expectedCode) {
        assertThatThrownBy(call).isInstanceOfSatisfying(PricingPolicyException.class,
            error -> assertThat(error.code()).isEqualTo(expectedCode));
    }
}
