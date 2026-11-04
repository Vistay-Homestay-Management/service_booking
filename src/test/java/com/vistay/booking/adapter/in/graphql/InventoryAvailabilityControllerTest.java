package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Partition;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.InventoryAvailabilityService;
import com.vistay.booking.application.InventoryScopePolicy;
import com.vistay.booking.adapter.in.graphql.InventoryAvailabilityController.PropertyAvailabilityInput;
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

class InventoryAvailabilityControllerTest {
    private static final UUID ACCOUNT_ID =
        UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PROFILE_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID PROPERTY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID BRANCH_ID =
        UUID.fromString("50000000-0000-0000-0000-000000000005");

    private InventoryAvailabilityService availability;
    private AuthorizationClient authorizations;

    @BeforeEach
    void setUp() {
        availability = mock(InventoryAvailabilityService.class);
        authorizations = mock(AuthorizationClient.class);
    }

    @Test
    void authenticatedAllReadStillResolvesPropertyAndPassesEvidence() {
        when(authorizations.require(ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .thenReturn(snapshot(Set.of(Scope.ALL), Set.of()));
        var authorityCalls = new java.util.concurrent.atomic.AtomicInteger();
        var controller = controller(propertyId -> {
            authorityCalls.incrementAndGet();
            return PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 23);
        });

        controller.propertyAvailability(jwt(ACCOUNT_ID.toString()),
            input(PROPERTY_ID.toString(), "2026-11-01", "2026-11-03"));

        var evidence = ArgumentCaptor.forClass(InventoryScopePolicy.ScopeEvidence.class);
        verify(availability).observe(eq(PROPERTY_ID),
            eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 3)),
            evidence.capture());
        verify(authorizations).require(
            ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN");
        org.assertj.core.api.Assertions.assertThat(authorityCalls).hasValue(1);
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().type()).isEqualTo("ALL");
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().propertyBranchId())
            .isEqualTo(BRANCH_ID);
        org.assertj.core.api.Assertions.assertThat(evidence.getValue().propertyMembershipVersion())
            .isEqualTo(23);
    }

    @Test
    void matchingBranchIsAcceptedButForeignAndUnsupportedScopesAreDenied() {
        when(authorizations.require(ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .thenReturn(snapshot(Set.of(Scope.PARTITION),
                Set.of(new Partition("BRANCH", BRANCH_ID))));
        var controller = controller(propertyId ->
            PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 3));

        controller.propertyAvailability(jwt(ACCOUNT_ID.toString()),
            input(PROPERTY_ID.toString(), "2026-11-01", "2026-11-02"));
        verify(availability).observe(eq(PROPERTY_ID), any(), any(), any());

        when(authorizations.require(ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .thenReturn(snapshot(Set.of(Scope.PARTITION),
                Set.of(new Partition("BRANCH", UUID.randomUUID()))));
        assertCode(controller, "INVENTORY_READ_FORBIDDEN");

        when(authorizations.require(ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .thenReturn(snapshot(Set.of(Scope.OWN, Scope.SELF), Set.of()));
        assertCode(controller, "INVENTORY_READ_FORBIDDEN");
    }

    @Test
    void notFoundUnmappedOrUnavailablePropertyFailsClosedBeforeInventoryRead() {
        when(authorizations.require(ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .thenReturn(snapshot(Set.of(Scope.ALL), Set.of()));

        assertCode(controller(propertyId -> PropertyPartitionClient.Resolution.notFound()),
            "PROPERTY_NOT_FOUND");
        assertCode(controller(propertyId -> PropertyPartitionClient.Resolution.unmapped()),
            "PROPERTY_PARTITION_UNMAPPED");
        assertCode(controller(propertyId -> PropertyPartitionClient.Resolution.unavailable()),
            "PROPERTY_PARTITION_UNAVAILABLE");
        verify(availability, never()).observe(any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidRangeEncodingAndAuthentication() {
        when(authorizations.require(ACCOUNT_ID, "inventory:read", "INVENTORY_READ_FORBIDDEN"))
            .thenReturn(snapshot(Set.of(Scope.ALL), Set.of()));
        var controller = controller(propertyId ->
            PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 3));

        assertThatThrownBy(() -> controller.propertyAvailability(
                jwt(ACCOUNT_ID.toString()),
                input(PROPERTY_ID.toString(), "01/11/2026", "2026-11-03")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo("INVALID_INVENTORY_RANGE"));
        assertThatThrownBy(() -> controller.propertyAvailability(
                jwt(ACCOUNT_ID.toString()),
                input("not-a-uuid", "2026-11-01", "2026-11-03")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo("INVALID_INVENTORY_RANGE"));
        for (var invalid : java.util.List.of(
            input(PROPERTY_ID.toString(), "2026-11-01T14:00:00Z", "2026-11-03"),
            input(PROPERTY_ID.toString(), "2026-11-03", "2026-11-03"),
            input(PROPERTY_ID.toString(), "2026-11-04", "2026-11-03")
        )) {
            assertThatThrownBy(() -> controller.propertyAvailability(
                    jwt(ACCOUNT_ID.toString()), invalid))
                .isInstanceOfSatisfying(PricingPolicyException.class,
                    error -> org.assertj.core.api.Assertions.assertThat(error.code())
                        .isEqualTo("INVALID_INVENTORY_RANGE"));
        }
        assertThatThrownBy(() -> controller.propertyAvailability(
                jwt(ACCOUNT_ID.toString()),
                input(PROPERTY_ID.toString(), null, "2026-11-03")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo("INVALID_INVENTORY_RANGE"));
        assertThatThrownBy(() -> controller.propertyAvailability(
                jwt(ACCOUNT_ID.toString()), null))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo("INVALID_INVENTORY_RANGE"));
        assertThatThrownBy(() -> controller.propertyAvailability(
                null, input(PROPERTY_ID.toString(), "2026-11-01", "2026-11-03")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo("UNAUTHENTICATED"));
        assertThatThrownBy(() -> controller.propertyAvailability(
                jwt("not-a-uuid"),
                input(PROPERTY_ID.toString(), "2026-11-01", "2026-11-03")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo("UNAUTHENTICATED"));
    }

    private InventoryAvailabilityController controller(PropertyPartitionClient properties) {
        return new InventoryAvailabilityController(availability, authorizations,
            new InventoryScopePolicy(properties));
    }

    private void assertCode(InventoryAvailabilityController controller, String code) {
        assertThatThrownBy(() -> controller.propertyAvailability(
                jwt(ACCOUNT_ID.toString()),
                input(PROPERTY_ID.toString(), "2026-11-01", "2026-11-02")))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> org.assertj.core.api.Assertions.assertThat(error.code())
                    .isEqualTo(code));
    }

    private AuthorizationClient.Snapshot snapshot(Set<Scope> scopes, Set<Partition> partitions) {
        return new AuthorizationClient.Snapshot(true, PROFILE_ID, 11,
            Map.of("inventory:read", scopes), Map.of("inventory:read", partitions),
            scopes.contains(Scope.ALL) ? Set.of("SUPER_ADMIN") : Set.of("MANAGER"));
    }

    private PropertyAvailabilityInput input(String propertyId, String checkIn, String checkOut) {
        return new PropertyAvailabilityInput(propertyId, checkIn, checkOut);
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token").header("alg", "none")
            .subject(subject).build();
    }
}
