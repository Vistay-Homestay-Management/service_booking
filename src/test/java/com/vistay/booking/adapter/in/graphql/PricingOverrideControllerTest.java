package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.vistay.booking.adapter.in.graphql.PricingController.CalculatePropertyPriceQuoteInput;
import com.vistay.booking.adapter.in.graphql.PricingController.PricingChannel;
import com.vistay.booking.adapter.in.graphql.PricingOverrideController.ApplyPropertyPriceOverrideInput;
import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Partition;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.PricingOverrideService;
import com.vistay.booking.application.PricingOverrideService.AppliedOverride;
import com.vistay.booking.application.PricingOverrideService.AuthorizationEvidence;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPolicyService;
import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingQuoteCalculator;
import com.vistay.booking.application.PricingQuoteCalculator.Command;
import com.vistay.booking.application.PricingQuoteCalculator.Quote;
import com.vistay.booking.application.PricingScopePolicy;
import com.vistay.booking.application.PropertyPartitionClient;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jwt.Jwt;

class PricingOverrideControllerTest {
    private static final String OVERRIDE = "pricing:override";
    private static final String LARGE = "pricing:approve-large-override";
    private static final UUID ACCOUNT_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ADMIN_PROFILE_ID = UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID PROFILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROPERTY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID BRANCH_ID = UUID.fromString("50000000-0000-0000-0000-000000000005");

    private PricingPolicyService pricing;
    private PricingQuoteCalculator quotes;
    private PricingOverrideService overrides;
    private AuthorizationClient authorizations;
    private ProfileVersion profile;
    private Quote baseQuote;

    @BeforeEach
    void setUp() {
        pricing = mock(PricingPolicyService.class);
        quotes = mock(PricingQuoteCalculator.class);
        overrides = mock(PricingOverrideService.class);
        authorizations = mock(AuthorizationClient.class);
        profile = profile();
        baseQuote = new PricingQuoteCalculator().calculate(profile, command());
    }

    @Test
    void exactReplayStillRequiresCurrentPermissionAndScopeButSkipsRecalculation() {
        var input = input("override-controller-replay", "800");
        var draft = input.toDraft();
        var replay = mock(AppliedOverride.class);
        when(authorizations.require(ACCOUNT_ID, OVERRIDE))
            .thenReturn(snapshot(OVERRIDE, Set.of(Scope.ALL), Set.of(), 10));
        when(overrides.replay(ACCOUNT_ID, draft)).thenReturn(Optional.of(replay));

        var result = controller(propertyId -> PropertyPartitionClient.Resolution.unavailable())
            .applyPropertyPriceOverride(jwt(ACCOUNT_ID.toString()), input);

        assertThat(result).isSameAs(replay);
        verify(authorizations).require(ACCOUNT_ID, OVERRIDE);
        verify(overrides).replay(ACCOUNT_ID, draft);
        verify(authorizations, never()).require(ACCOUNT_ID, LARGE);
        verifyNoInteractions(pricing, quotes);
        verify(overrides, never()).apply(any(), any(), any(), any());
    }

    @Test
    void scopeFailureStopsBeforeReplayOrDatabaseReads() {
        var input = input("override-controller-scope", "800");
        when(authorizations.require(ACCOUNT_ID, OVERRIDE))
            .thenReturn(snapshot(OVERRIDE, Set.of(Scope.PARTITION), Set.of(), 10));

        assertCode(() -> controller(propertyId ->
                PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 3))
            .applyPropertyPriceOverride(jwt(ACCOUNT_ID.toString()), input),
            "FORBIDDEN_PRICING_SCOPE");

        verifyNoInteractions(overrides, pricing, quotes);
    }

    @Test
    void exactTwentyPercentUsesOnlyOverridePermissionAndCapturesAllScopeEvidence() {
        var input = input("override-controller-exact", "800");
        var draft = input.toDraft();
        var applied = mock(AppliedOverride.class);
        when(authorizations.require(ACCOUNT_ID, OVERRIDE))
            .thenReturn(snapshot(OVERRIDE, Set.of(Scope.ALL), Set.of(), 10));
        when(overrides.replay(ACCOUNT_ID, draft)).thenReturn(Optional.empty());
        when(pricing.requireVersion(PROFILE_ID)).thenReturn(profile);
        when(quotes.calculate(profile, draft.quote())).thenReturn(baseQuote);
        when(overrides.apply(eq(profile), eq(baseQuote), eq(draft), any()))
            .thenReturn(applied);

        var result = controller(propertyId -> PropertyPartitionClient.Resolution.unavailable())
            .applyPropertyPriceOverride(jwt(ACCOUNT_ID.toString()), input);

        assertThat(result).isSameAs(applied);
        var evidence = captureEvidence();
        assertThat(evidence.actorAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(evidence.actorProfileId()).isEqualTo(ADMIN_PROFILE_ID);
        assertThat(evidence.authorizationVersion()).isEqualTo(10);
        assertThat(evidence.overrideScope().type()).isEqualTo("ALL");
        assertThat(evidence.overrideScope().partitionId()).isNull();
        assertThat(evidence.largeApprovalAuthorizationVersion()).isNull();
        assertThat(evidence.largeApprovalScope()).isNull();
        verify(authorizations, never()).require(ACCOUNT_ID, LARGE);
    }

    @Test
    void greaterThanTwentyPercentRequiresIndependentLargeApprovalAndScopeEvidence() {
        var input = input("override-controller-large", "799");
        var draft = input.toDraft();
        var applied = mock(AppliedOverride.class);
        when(authorizations.require(ACCOUNT_ID, OVERRIDE))
            .thenReturn(snapshot(OVERRIDE, Set.of(Scope.PARTITION),
                Set.of(new Partition("BRANCH", BRANCH_ID)), 10));
        when(authorizations.require(ACCOUNT_ID, LARGE))
            .thenReturn(snapshot(LARGE, Set.of(Scope.ALL), Set.of(), 12));
        when(overrides.replay(ACCOUNT_ID, draft)).thenReturn(Optional.empty());
        when(pricing.requireVersion(PROFILE_ID)).thenReturn(profile);
        when(quotes.calculate(profile, draft.quote())).thenReturn(baseQuote);
        when(overrides.apply(eq(profile), eq(baseQuote), eq(draft), any()))
            .thenReturn(applied);

        var result = controller(propertyId ->
                PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 3))
            .applyPropertyPriceOverride(jwt(ACCOUNT_ID.toString()), input);

        assertThat(result).isSameAs(applied);
        var evidence = captureEvidence();
        assertThat(evidence.overrideScope().type()).isEqualTo("BRANCH");
        assertThat(evidence.overrideScope().partitionId()).isEqualTo(BRANCH_ID);
        assertThat(evidence.largeApprovalAuthorizationVersion()).isEqualTo(12);
        assertThat(evidence.largeApprovalScope().type()).isEqualTo("ALL");
        assertThat(evidence.largeApprovalScope().partitionId()).isNull();
        verify(authorizations).require(ACCOUNT_ID, LARGE);
    }

    @Test
    void largeApprovalScopeFailurePreventsApplyingOverride() {
        var input = input("override-controller-large-scope", "799");
        var draft = input.toDraft();
        when(authorizations.require(ACCOUNT_ID, OVERRIDE))
            .thenReturn(snapshot(OVERRIDE, Set.of(Scope.ALL), Set.of(), 10));
        when(authorizations.require(ACCOUNT_ID, LARGE))
            .thenReturn(snapshot(LARGE, Set.of(Scope.PARTITION), Set.of(), 12));
        when(overrides.replay(ACCOUNT_ID, draft)).thenReturn(Optional.empty());
        when(pricing.requireVersion(PROFILE_ID)).thenReturn(profile);
        when(quotes.calculate(profile, draft.quote())).thenReturn(baseQuote);

        assertCode(() -> controller(propertyId ->
                PropertyPartitionClient.Resolution.mapped(BRANCH_ID, 3))
            .applyPropertyPriceOverride(jwt(ACCOUNT_ID.toString()), input),
            "FORBIDDEN_PRICING_SCOPE");
        verify(overrides, never()).apply(any(), any(), any(), any());
    }

    @Test
    void largeApprovalMustBelongToTheSameAdministratorProfile() {
        var input = input("override-controller-profile-mismatch", "799");
        var draft = input.toDraft();
        when(authorizations.require(ACCOUNT_ID, OVERRIDE))
            .thenReturn(snapshot(OVERRIDE, Set.of(Scope.ALL), Set.of(), 10));
        when(authorizations.require(ACCOUNT_ID, LARGE))
            .thenReturn(new AuthorizationClient.Snapshot(
                true, UUID.randomUUID(), 12,
                Map.of(LARGE, Set.of(Scope.ALL)), Map.of(LARGE, Set.of())));
        when(overrides.replay(ACCOUNT_ID, draft)).thenReturn(Optional.empty());
        when(pricing.requireVersion(PROFILE_ID)).thenReturn(profile);
        when(quotes.calculate(profile, draft.quote())).thenReturn(baseQuote);

        assertCode(() -> controller(propertyId -> PropertyPartitionClient.Resolution.unavailable())
            .applyPropertyPriceOverride(jwt(ACCOUNT_ID.toString()), input),
            "INVALID_OVERRIDE_AUTHORIZATION");
        verify(overrides, never()).apply(any(), any(), any(), any());
    }

    @Test
    void rejectsMissingMalformedAuthenticationAndInvalidTargetInput() {
        var controller = controller(propertyId -> PropertyPartitionClient.Resolution.missing());
        var valid = input("override-controller-auth", "800");
        assertCode(() -> controller.applyPropertyPriceOverride(null, valid), "UNAUTHENTICATED");
        assertCode(() -> controller.applyPropertyPriceOverride(jwt("not-a-uuid"), valid),
            "INVALID_TOKEN");
        assertCode(() -> controller.applyPropertyPriceOverride(jwtWithoutSubject(), valid),
            "INVALID_TOKEN");
        assertCode(() -> input("override-controller-invalid", "800.50").toDraft(),
            "INVALID_OVERRIDE_INPUT");
        assertCode(() -> new ApplyPropertyPriceOverrideInput(
            "override-controller-null", null, baseQuote.fingerprint(), "800",
            "Valid override reason", "override-controller-null").toDraft(),
            "INVALID_OVERRIDE_INPUT");
    }

    private PricingOverrideController controller(PropertyPartitionClient properties) {
        return new PricingOverrideController(pricing, quotes, overrides, authorizations,
            new PricingScopePolicy(properties));
    }

    private AuthorizationEvidence captureEvidence() {
        var captor = ArgumentCaptor.forClass(AuthorizationEvidence.class);
        verify(overrides).apply(eq(profile), eq(baseQuote), any(), captor.capture());
        return captor.getValue();
    }

    private ApplyPropertyPriceOverrideInput input(String key, String target) {
        return new ApplyPropertyPriceOverrideInput(
            key,
            new CalculatePropertyPriceQuoteInput(
                PROFILE_ID, PROPERTY_ID, "2026-11-01", "2026-11-02", 1,
                PricingChannel.ADMIN_PORTAL, true, null),
            baseQuote.fingerprint(), target,
            "Approved lodging reduction for guest recovery", "corr-" + key
        );
    }

    private AuthorizationClient.Snapshot snapshot(String permission, Set<Scope> scopes,
        Set<Partition> partitions, long version) {
        return new AuthorizationClient.Snapshot(
            true, ADMIN_PROFILE_ID, version,
            Map.of(permission, scopes), Map.of(permission, partitions)
        );
    }

    private Command command() {
        return new Command(PROFILE_ID, PROPERTY_ID, LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 2), 1, "ADMIN_PORTAL", true, null);
    }

    private ProfileVersion profile() {
        return new ProfileVersion(
            PROFILE_ID, PROPERTY_ID, 1, "2026-01-01", "2027-01-01", "1000",
            7_000, 5_500, "VND", "14:00", "12:00", 120, 15, 500, 2_000,
            "Pricing override controller profile", ACCOUNT_ID, "override-controller",
            "2026-10-30T00:00:00Z", List.of()
        );
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(subject)
            .build();
    }

    private Jwt jwtWithoutSubject() {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("client_id", "backoffice")
            .build();
    }

    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(code));
    }
}
