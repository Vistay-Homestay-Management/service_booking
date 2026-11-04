package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.PricingOverrideService;
import com.vistay.booking.application.PricingOverrideService.AppliedOverride;
import com.vistay.booking.application.PricingOverrideService.AuthorizationEvidence;
import com.vistay.booking.application.PricingOverrideService.Draft;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPolicyService;
import com.vistay.booking.application.PricingQuoteCalculator;
import com.vistay.booking.application.PricingScopePolicy;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class PricingOverrideController {
    private static final String OVERRIDE_PERMISSION = "pricing:override";
    private static final String LARGE_OVERRIDE_PERMISSION =
        "pricing:approve-large-override";

    private final PricingPolicyService pricing;
    private final PricingQuoteCalculator quotes;
    private final PricingOverrideService overrides;
    private final AuthorizationClient authorizations;
    private final PricingScopePolicy scopes;

    public PricingOverrideController(
        PricingPolicyService pricing,
        PricingQuoteCalculator quotes,
        PricingOverrideService overrides,
        AuthorizationClient authorizations,
        PricingScopePolicy scopes
    ) {
        this.pricing = pricing;
        this.quotes = quotes;
        this.overrides = overrides;
        this.authorizations = authorizations;
        this.scopes = scopes;
    }

    @MutationMapping
    public AppliedOverride applyPropertyPriceOverride(
        @AuthenticationPrincipal Jwt jwt,
        @Argument ApplyPropertyPriceOverrideInput input
    ) {
        var actorAccountId = accountId(jwt);
        var draft = input.toDraft();
        var overrideAuthorization = authorizations.require(actorAccountId,
            OVERRIDE_PERMISSION);
        var overrideScope = scopes.require(overrideAuthorization, OVERRIDE_PERMISSION,
            draft.quote().propertyId());

        // Exact retries return the original immutable artifact, even after its
        // expiry. Authorization and property scope are still checked first.
        var replay = overrides.replay(actorAccountId, draft);
        if (replay.isPresent()) return replay.get();

        var profile = pricing.requireVersion(draft.quote().rateProfileVersionId());
        var baseQuote = quotes.calculate(profile, draft.quote());
        if (!draft.expectedBaseQuoteFingerprint().equals(baseQuote.fingerprint())) {
            throw new PricingPolicyException("STALE_BASE_QUOTE",
                "Base quote fingerprint is stale or does not match the recalculated quote");
        }

        Long largeAuthorizationVersion = null;
        PricingScopePolicy.ScopeEvidence largeScope = null;
        long beforeLodging = parseCalculatedAmount(baseQuote.lodgingAmount());
        if (PricingOverrideService.requiresLargeApproval(
            beforeLodging, draft.targetLodgingAmount())) {
            var largeAuthorization = authorizations.require(actorAccountId,
                LARGE_OVERRIDE_PERMISSION);
            if (!overrideAuthorization.profileId().equals(largeAuthorization.profileId())) {
                throw new PricingPolicyException("INVALID_OVERRIDE_AUTHORIZATION",
                    "Override permissions must resolve to the same administrator profile");
            }
            largeScope = scopes.require(largeAuthorization, LARGE_OVERRIDE_PERMISSION,
                draft.quote().propertyId());
            largeAuthorizationVersion = largeAuthorization.authorizationVersion();
        }

        return overrides.apply(profile, baseQuote, draft, new AuthorizationEvidence(
            actorAccountId,
            overrideAuthorization.profileId(),
            overrideAuthorization.authorizationVersion(),
            overrideScope,
            largeAuthorizationVersion,
            largeScope
        ));
    }

    private static UUID accountId(Jwt jwt) {
        if (jwt == null) {
            throw new PricingPolicyException("UNAUTHENTICATED",
                "Authentication is required");
        }
        try {
            if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                throw new IllegalArgumentException("missing subject");
            }
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException invalid) {
            throw new PricingPolicyException("INVALID_TOKEN",
                "Token subject is invalid");
        }
    }

    private static long parseCalculatedAmount(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw new PricingPolicyException("INVALID_STORED_PRICING_POLICY",
                "Calculated lodging amount is not a whole-VND value");
        }
    }

    public record ApplyPropertyPriceOverrideInput(
        String idempotencyKey,
        PricingController.CalculatePropertyPriceQuoteInput quote,
        String expectedBaseQuoteFingerprint,
        String targetLodgingAmount,
        String reason,
        String correlationId
    ) {
        Draft toDraft() {
            try {
                if (targetLodgingAmount == null
                    || !targetLodgingAmount.matches("^[0-9]+$")) {
                    throw new NumberFormatException("targetLodgingAmount");
                }
                return new Draft(
                    idempotencyKey,
                    quote.toCommand(),
                    expectedBaseQuoteFingerprint,
                    Long.parseLong(targetLodgingAmount),
                    reason,
                    correlationId
                );
            } catch (NumberFormatException | NullPointerException invalid) {
                throw new PricingPolicyException("INVALID_OVERRIDE_INPUT",
                    "Override quote fields and target whole-VND amount are required");
            }
        }
    }
}
