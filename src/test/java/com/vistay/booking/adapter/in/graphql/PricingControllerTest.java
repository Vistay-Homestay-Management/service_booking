package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.booking.adapter.in.graphql.PricingController.CalculatePropertyPriceQuoteInput;
import com.vistay.booking.adapter.in.graphql.PricingController.PricingChannel;
import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPolicyService;
import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingQuoteCalculator;
import com.vistay.booking.application.PricingScopePolicy;
import com.vistay.booking.application.PropertyPartitionClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class PricingControllerTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID PROFILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROPERTY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private PricingPolicyService pricing;
    private AuthorizationClient authorizations;

    @BeforeEach
    void setUp() {
        pricing = mock(PricingPolicyService.class);
        authorizations = mock(AuthorizationClient.class);
    }

    @Test
    void calculateQuoteRequiresReadPermissionAndReturnsCalculatorResult() {
        var snapshot = snapshot(Set.of(Scope.ALL));
        var profile = profile();
        when(authorizations.require(ACCOUNT_ID, "pricing:read")).thenReturn(snapshot);
        when(pricing.requireVersion(PROFILE_ID)).thenReturn(profile);
        var controller = controller(propertyId -> PropertyPartitionClient.Resolution.unavailable());
        var input = input("2026-11-01", "2026-11-02", null, null, null);

        var quote = controller.calculatePropertyPriceQuote(jwt(ACCOUNT_ID.toString()), input);

        assertThat(quote.propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(quote.stayDays()).isEqualTo(1);
        assertThat(quote.totalAmount()).isEqualTo("100000");
        verify(authorizations).require(ACCOUNT_ID, "pricing:read");
        verify(pricing).requireVersion(PROFILE_ID);
    }

    @Test
    void scopeFailureStopsBeforeLoadingPricingProfile() {
        when(authorizations.require(ACCOUNT_ID, "pricing:read"))
            .thenReturn(snapshot(Set.of(Scope.PARTITION)));
        var controller = controller(propertyId -> PropertyPartitionClient.Resolution.missing());

        assertThatThrownBy(() -> controller.calculatePropertyPriceQuote(
            jwt(ACCOUNT_ID.toString()), input("2026-11-01", "2026-11-02", 1, false, null)))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("FORBIDDEN_PRICING_SCOPE"));
        verify(pricing, never()).requireVersion(PROFILE_ID);
    }

    @Test
    void rejectsMissingOrMalformedAuthenticationSubject() {
        var controller = controller(propertyId -> PropertyPartitionClient.Resolution.missing());
        var input = input("2026-11-01", "2026-11-02", 1, false, null);

        assertControllerCode(() -> controller.calculatePropertyPriceQuote(null, input),
            "UNAUTHENTICATED");
        assertControllerCode(() -> controller.calculatePropertyPriceQuote(jwt("not-a-uuid"), input),
            "INVALID_TOKEN");
    }

    @Test
    void quoteInputUsesDocumentedDefaults() {
        var command = input("2026-11-01", "2026-11-02", null, null, null).toCommand();

        assertThat(command.rateProfileVersionId()).isEqualTo(PROFILE_ID);
        assertThat(command.propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(command.checkInDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(command.checkOutDate()).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThat(command.guestCount()).isEqualTo(1);
        assertThat(command.channel()).isEqualTo("CUSTOMER_WEB");
        assertThat(command.staffCreated()).isFalse();
        assertThat(command.commissionBps()).isNull();
    }

    @Test
    void quoteInputMapsStaffCommissionAndRejectsMalformedDates() {
        var command = new CalculatePropertyPriceQuoteInput(
            PROFILE_ID, PROPERTY_ID, "2026-11-01", "2026-11-03", 3,
            PricingChannel.ADMIN_PORTAL, true, 1_000
        ).toCommand();
        assertThat(command.guestCount()).isEqualTo(3);
        assertThat(command.staffCreated()).isTrue();
        assertThat(command.commissionBps()).isEqualTo(1_000);

        assertControllerCode(() -> input("01/11/2026", "2026-11-02", 1, false, null)
            .toCommand(), "INVALID_QUOTE_INPUT");
        assertControllerCode(() -> input(null, "2026-11-02", 1, false, null)
            .toCommand(), "INVALID_QUOTE_INPUT");
    }

    private PricingController controller(PropertyPartitionClient properties) {
        return new PricingController(
            pricing,
            new PricingQuoteCalculator(),
            authorizations,
            new PricingScopePolicy(properties)
        );
    }

    private CalculatePropertyPriceQuoteInput input(
        String checkIn,
        String checkOut,
        Integer guestCount,
        Boolean staffCreated,
        Integer commissionBps
    ) {
        return new CalculatePropertyPriceQuoteInput(
            PROFILE_ID, PROPERTY_ID, checkIn, checkOut, guestCount,
            PricingChannel.CUSTOMER_WEB, staffCreated, commissionBps
        );
    }

    private AuthorizationClient.Snapshot snapshot(Set<Scope> scopes) {
        return new AuthorizationClient.Snapshot(
            true, UUID.randomUUID(), 1,
            Map.of("pricing:read", scopes), Map.of()
        );
    }

    private ProfileVersion profile() {
        return new ProfileVersion(
            PROFILE_ID, PROPERTY_ID, 1, "2026-01-01", "2027-01-01", "100000",
            7_000, 5_500, "VND", "14:00", "12:00", 120, 15, 500, 2_000,
            "Pricing controller test", ACCOUNT_ID, "pricing-controller-test",
            "2026-10-30T00:00:00Z", List.of()
        );
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(subject)
            .build();
    }

    private void assertControllerCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
