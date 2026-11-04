package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PricingPublicationFingerprintContractTest {
    @Test
    void matchesCrossLanguageUtf8VectorAndIgnoresSourceRuleOrder() throws Exception {
        var json = JsonMapper.builder().build();
        var raw = Files.readString(Path.of("contracts", "fixtures",
            "pricing-v1-publication-fingerprint.v1.json"), StandardCharsets.UTF_8);
        var fixture = json.readValue(raw, Fixture.class);
        var service = new PricingPublicationService(null, json, null, Clock.systemUTC());

        assertThat(fixture.schemaVersion()).isEqualTo("PRICING_PUBLICATION_V1");
        assertThat(service.profileFingerprint(fixture.profile())).isEqualTo(fixture.sha256());

        var reversedRules = fixture.profile().rules().reversed();
        assertThat(service.profileFingerprint(withRules(fixture.profile(), reversedRules)))
            .isEqualTo(fixture.sha256());
    }

    private ProfileVersion withRules(
        ProfileVersion profile,
        List<PricingPolicyService.RuleVersion> rules
    ) {
        return new ProfileVersion(profile.id(), profile.propertyId(), profile.version(),
            profile.effectiveFrom(), profile.effectiveToExclusive(), profile.baseDailyRate(),
            profile.mediumDailyFactorBps(), profile.longDailyFactorBps(), profile.currency(),
            profile.checkInTime(), profile.checkOutTime(), profile.turnoverMinutes(),
            profile.quoteTtlMinutes(), profile.commissionDefaultBps(),
            profile.commissionMaxBps(), profile.reason(),
            profile.authoredByAdminAccountId(), profile.correlationId(), profile.authoredAt(),
            rules);
    }

    private record Fixture(
        String schemaVersion,
        ProfileVersion profile,
        String sha256
    ) {}
}
