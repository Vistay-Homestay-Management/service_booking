package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.adapter.in.graphql.PricingController.PricingChannel;
import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPublicationService;
import com.vistay.booking.application.PricingPublicationService.AuthorizationEvidence;
import com.vistay.booking.application.PricingPublicationService.Draft;
import com.vistay.booking.application.PricingPublicationService.Publication;
import com.vistay.booking.application.PricingPublicationService.PublicationPreview;
import com.vistay.booking.application.PricingQuoteSnapshotService;
import com.vistay.booking.application.PricingQuoteSnapshotService.Input;
import com.vistay.booking.application.PricingQuoteSnapshotService.PublishedQuotePreview;
import com.vistay.booking.application.PricingQuoteSnapshotService.QuoteSnapshot;
import com.vistay.booking.application.PricingScopePolicy;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class PricingLifecycleController {
    private static final String READ = "pricing:read";
    private static final String APPROVE = "pricing:approve";

    private final PricingPublicationService publications;
    private final PricingQuoteSnapshotService snapshots;
    private final AuthorizationClient authorizations;
    private final PricingScopePolicy scopes;

    public PricingLifecycleController(
        PricingPublicationService publications,
        PricingQuoteSnapshotService snapshots,
        AuthorizationClient authorizations,
        PricingScopePolicy scopes
    ) {
        this.publications = publications;
        this.snapshots = snapshots;
        this.authorizations = authorizations;
        this.scopes = scopes;
    }

    @QueryMapping
    public PublicationPreview previewPropertyRateProfilePublication(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID rateProfileVersionId
    ) {
        var snapshot = authorizations.require(accountId(jwt), READ);
        var preview = publications.preview(rateProfileVersionId);
        scopes.require(snapshot, READ, preview.profile().propertyId());
        return preview;
    }

    @QueryMapping
    public Publication activePropertyRateProfileVersion(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID propertyId,
        @Argument String checkInDate,
        @Argument String checkOutDate
    ) {
        var snapshot = authorizations.require(accountId(jwt), READ);
        scopes.require(snapshot, READ, propertyId);
        try {
            return publications.active(propertyId, LocalDate.parse(checkInDate),
                LocalDate.parse(checkOutDate)).publication();
        } catch (DateTimeParseException | NullPointerException invalid) {
            throw new PricingPolicyException("INVALID_STAY_RANGE",
                "Stay dates must use ISO-8601 format");
        }
    }

    @QueryMapping
    public PublishedQuotePreview previewPublishedPropertyPriceQuote(
        @AuthenticationPrincipal Jwt jwt,
        @Argument PublishedPropertyPriceQuoteInput input
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, READ);
        var parsed = input.toInput();
        scopes.require(snapshot, READ, parsed.propertyId());
        return snapshots.preview(parsed);
    }

    @QueryMapping
    public QuoteSnapshot propertyPriceQuoteSnapshot(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID id
    ) {
        var accountId = accountId(jwt);
        var authorization = authorizations.require(accountId, READ);
        var result = snapshots.requireSnapshot(id);
        scopes.require(authorization, READ, result.propertyId());
        return result;
    }

    @MutationMapping
    public Publication publishPropertyRateProfileVersion(
        @AuthenticationPrincipal Jwt jwt,
        @Argument PublishPropertyRateProfileVersionInput input
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, APPROVE);
        var profile = publications.preview(input.rateProfileVersionId()).profile();
        var scope = scopes.require(snapshot, APPROVE, profile.propertyId());
        return publications.publish(input.toDraft(), profile,
            new AuthorizationEvidence(accountId, snapshot.profileId(),
                snapshot.authorizationVersion(), scope));
    }

    @MutationMapping
    public QuoteSnapshot createPropertyPriceQuoteSnapshot(
        @AuthenticationPrincipal Jwt jwt,
        @Argument CreatePropertyPriceQuoteSnapshotInput input
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, READ);
        var draft = input.toDraft();
        var scope = scopes.require(snapshot, READ, draft.input().propertyId());
        return snapshots.create(draft,
            new PricingQuoteSnapshotService.AuthorizationEvidence(
                accountId, snapshot.profileId(), snapshot.authorizationVersion(), scope));
    }

    private static UUID accountId(Jwt jwt) {
        if (jwt == null) {
            throw new PricingPolicyException("UNAUTHENTICATED", "Authentication is required");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException invalid) {
            throw new PricingPolicyException("INVALID_TOKEN", "Token subject is invalid");
        }
    }

    public record PublishPropertyRateProfileVersionInput(
        String idempotencyKey,
        UUID rateProfileVersionId,
        String expectedProfileFingerprint,
        UUID expectedCurrentPublicationId,
        String reason,
        String correlationId
    ) {
        Draft toDraft() {
            return new Draft(idempotencyKey, rateProfileVersionId,
                expectedProfileFingerprint, expectedCurrentPublicationId,
                reason, correlationId);
        }
    }

    public record PublishedPropertyPriceQuoteInput(
        UUID propertyId,
        String checkInDate,
        String checkOutDate,
        Integer guestCount,
        PricingChannel channel,
        Boolean staffCreated,
        Integer commissionBps
    ) {
        Input toInput() {
            try {
                return new Input(propertyId, LocalDate.parse(checkInDate),
                    LocalDate.parse(checkOutDate), guestCount == null ? 1 : guestCount,
                    channel == null ? null : channel.name(),
                    staffCreated != null && staffCreated, commissionBps);
            } catch (DateTimeParseException | NullPointerException invalid) {
                throw new PricingPolicyException("INVALID_STAY_RANGE",
                    "Stay dates must use ISO-8601 format");
            }
        }
    }

    public record CreatePropertyPriceQuoteSnapshotInput(
        String idempotencyKey,
        PublishedPropertyPriceQuoteInput quote,
        UUID expectedRateProfileVersionId,
        String expectedBaseQuoteFingerprint,
        UUID manualOverrideId,
        String reason,
        String correlationId
    ) {
        PricingQuoteSnapshotService.Draft toDraft() {
            if (quote == null) {
                throw new PricingPolicyException("INVALID_QUOTE_SNAPSHOT_INPUT",
                    "Published quote input is required");
            }
            return new PricingQuoteSnapshotService.Draft(
                idempotencyKey, quote.toInput(), expectedRateProfileVersionId,
                expectedBaseQuoteFingerprint, manualOverrideId, reason, correlationId);
        }
    }
}
