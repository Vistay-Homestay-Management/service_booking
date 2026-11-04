package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.vistay.booking.adapter.in.graphql.PricingController.PricingChannel;
import com.vistay.booking.adapter.in.graphql.PricingLifecycleController.CreatePropertyPriceQuoteSnapshotInput;
import com.vistay.booking.adapter.in.graphql.PricingLifecycleController.PublishPropertyRateProfileVersionInput;
import com.vistay.booking.adapter.in.graphql.PricingLifecycleController.PublishedPropertyPriceQuoteInput;
import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.AuthorizationClient.Scope;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingPublicationService;
import com.vistay.booking.application.PricingPublicationService.PublicationPreview;
import com.vistay.booking.application.PricingQuoteSnapshotService;
import com.vistay.booking.application.PricingScopePolicy;
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

class PricingLifecycleControllerTest {
    private static final UUID ACCOUNT_ID =
        UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final UUID ADMIN_PROFILE_ID =
        UUID.fromString("40000000-0000-0000-0000-000000000004");
    private static final UUID PROFILE_ID =
        UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROPERTY_ID =
        UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final String FINGERPRINT = "a".repeat(64);

    private PricingPublicationService publications;
    private PricingQuoteSnapshotService snapshots;
    private AuthorizationClient authorizations;
    private PricingLifecycleController controller;

    @BeforeEach
    void setUp() {
        publications = mock(PricingPublicationService.class);
        snapshots = mock(PricingQuoteSnapshotService.class);
        authorizations = mock(AuthorizationClient.class);
        controller = new PricingLifecycleController(publications, snapshots, authorizations,
            new PricingScopePolicy(propertyId -> PropertyPartitionClient.Resolution.unavailable()));
    }

    @Test
    void publicationRequiresApproveAndPassesImmutableAuthorizationEvidence() {
        when(authorizations.require(ACCOUNT_ID, "pricing:approve"))
            .thenReturn(snapshot("pricing:approve", Set.of(Scope.ALL)));
        when(publications.preview(PROFILE_ID))
            .thenReturn(new PublicationPreview(profile(), FINGERPRINT, null, null));
        var input = new PublishPropertyRateProfileVersionInput(
            "publish-controller-01", PROFILE_ID, FINGERPRINT, null,
            "Publish approved property pricing", "pricing-controller-test");

        controller.publishPropertyRateProfileVersion(jwt(ACCOUNT_ID.toString()), input);

        var evidence = ArgumentCaptor.forClass(
            PricingPublicationService.AuthorizationEvidence.class);
        var draft = ArgumentCaptor.forClass(PricingPublicationService.Draft.class);
        verify(publications).publish(draft.capture(), eq(profile()), evidence.capture());
        assertThat(draft.getValue().expectedCurrentPublicationId()).isNull();
        assertThat(evidence.getValue().actorAccountId()).isEqualTo(ACCOUNT_ID);
        assertThat(evidence.getValue().actorProfileId()).isEqualTo(ADMIN_PROFILE_ID);
        assertThat(evidence.getValue().authorizationVersion()).isEqualTo(13);
        assertThat(evidence.getValue().scope().type()).isEqualTo("ALL");
    }

    @Test
    void publishedPreviewUsesServerSideSelectionAfterReadScopeCheck() {
        when(authorizations.require(ACCOUNT_ID, "pricing:read"))
            .thenReturn(snapshot("pricing:read", Set.of(Scope.ALL)));
        var input = quoteInput("2026-11-01", "2026-11-03");

        controller.previewPublishedPropertyPriceQuote(jwt(ACCOUNT_ID.toString()), input);

        var captured = ArgumentCaptor.forClass(PricingQuoteSnapshotService.Input.class);
        verify(snapshots).preview(captured.capture());
        assertThat(captured.getValue().propertyId()).isEqualTo(PROPERTY_ID);
        assertThat(captured.getValue().checkInDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(captured.getValue().checkOutDate()).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(captured.getValue().guestCount()).isEqualTo(1);
        assertThat(captured.getValue().channel()).isEqualTo("ADMIN_PORTAL");
    }

    @Test
    void snapshotCommandCarriesExpectedProfileFingerprintAndExactOverrideId() {
        when(authorizations.require(ACCOUNT_ID, "pricing:read"))
            .thenReturn(snapshot("pricing:read", Set.of(Scope.ALL)));
        var overrideId = UUID.randomUUID();
        var input = new CreatePropertyPriceQuoteSnapshotInput(
            "snapshot-controller-01", quoteInput("2026-11-01", "2026-11-03"),
            PROFILE_ID, FINGERPRINT, overrideId,
            "Persist immutable pricing snapshot", "pricing-controller-test");

        controller.createPropertyPriceQuoteSnapshot(jwt(ACCOUNT_ID.toString()), input);

        var draft = ArgumentCaptor.forClass(PricingQuoteSnapshotService.Draft.class);
        var evidence = ArgumentCaptor.forClass(
            PricingQuoteSnapshotService.AuthorizationEvidence.class);
        verify(snapshots).create(draft.capture(), evidence.capture());
        assertThat(draft.getValue().expectedRateProfileVersionId()).isEqualTo(PROFILE_ID);
        assertThat(draft.getValue().expectedBaseQuoteFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(draft.getValue().manualOverrideId()).isEqualTo(overrideId);
        assertThat(evidence.getValue().scope().type()).isEqualTo("ALL");
    }

    @Test
    void malformedInputOrDeniedScopeStopsBeforeLifecycleService() {
        when(authorizations.require(ACCOUNT_ID, "pricing:read"))
            .thenReturn(snapshot("pricing:read", Set.of(Scope.PARTITION)));
        var malformed = quoteInput("01/11/2026", "2026-11-03");

        assertThatThrownBy(() -> controller.previewPublishedPropertyPriceQuote(
                jwt(ACCOUNT_ID.toString()), malformed))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_STAY_RANGE"));
        verify(snapshots, never()).preview(any());

        var valid = quoteInput("2026-11-01", "2026-11-03");
        assertThatThrownBy(() -> controller.previewPublishedPropertyPriceQuote(
                jwt(ACCOUNT_ID.toString()), valid))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("PROPERTY_PARTITION_UNAVAILABLE"));
        verify(snapshots, never()).preview(any());
    }

    @Test
    void lifecycleEndpointsRejectMissingOrMalformedAuthentication() {
        var input = quoteInput("2026-11-01", "2026-11-03");

        assertThatThrownBy(() -> controller.previewPublishedPropertyPriceQuote(null, input))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("UNAUTHENTICATED"));
        assertThatThrownBy(() -> controller.previewPublishedPropertyPriceQuote(
                jwt("not-a-uuid"), input))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_TOKEN"));
    }

    private PublishedPropertyPriceQuoteInput quoteInput(String checkIn, String checkOut) {
        return new PublishedPropertyPriceQuoteInput(
            PROPERTY_ID, checkIn, checkOut, null,
            PricingChannel.ADMIN_PORTAL, false, null);
    }

    private AuthorizationClient.Snapshot snapshot(String permission, Set<Scope> scopes) {
        return new AuthorizationClient.Snapshot(true, ADMIN_PROFILE_ID, 13,
            Map.of(permission, scopes), Map.of());
    }

    private ProfileVersion profile() {
        return new ProfileVersion(
            PROFILE_ID, PROPERTY_ID, 1, "2026-11-01", null, "1000000",
            7_000, 5_500, "VND", "14:00", "12:00", 120, 15, 500, 2_000,
            "Pricing lifecycle controller test", ACCOUNT_ID,
            "pricing-controller-test", "2026-10-30T00:00:00Z", List.of());
    }

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject(subject)
            .build();
    }
}
