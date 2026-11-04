package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PricingPolicyValidatorTest {
    private static final LocalDate EFFECTIVE_FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate EFFECTIVE_TO = LocalDate.of(2027, 11, 1);

    private final PricingPolicyValidator validator = new PricingPolicyValidator();

    @Test
    void acceptsVersionedV1DefaultsAndShortStayRule() {
        assertThatCode(() -> validator.validate(validDraft(List.of(
            rule("weekday-rate", "FIXED_DAILY_RATE", 900_000, 100,
                List.of(1, 2, 3, 4, 5), List.of("ADMIN_PORTAL"), 1, 29)
        )))).doesNotThrowAnyException();
    }

    @Test
    void rejectsLongFactorAboveMediumFactor() {
        var draft = new PricingPolicyValidator.Draft(
            "pricing-factor-1", UUID.randomUUID(), EFFECTIVE_FROM, EFFECTIVE_TO,
            1_000_000, 7_000, 7_001, LocalTime.of(14, 0), LocalTime.of(12, 0),
            120, 15, 500, 2_000, "Create the initial pricing version", null, List.of()
        );

        assertCode(draft, "INVALID_LONG_FACTOR");
    }

    @Test
    void rejectsCommissionAboveTwentyPercentCeiling() {
        var draft = new PricingPolicyValidator.Draft(
            "pricing-commission-1", UUID.randomUUID(), EFFECTIVE_FROM, EFFECTIVE_TO,
            1_000_000, 7_000, 5_500, LocalTime.of(14, 0), LocalTime.of(12, 0),
            120, 15, 500, 2_001, "Create the initial pricing version", null, List.of()
        );

        assertCode(draft, "INVALID_COMMISSION_POLICY");
    }

    @Test
    void rejectsTurnoverLongerThanCheckoutToCheckinWindow() {
        var draft = new PricingPolicyValidator.Draft(
            "pricing-turnover-1", UUID.randomUUID(), EFFECTIVE_FROM, EFFECTIVE_TO,
            1_000_000, 7_000, 5_500, LocalTime.of(14, 0), LocalTime.of(12, 0),
            121, 15, 500, 2_000, "Create the initial pricing version", null, List.of()
        );

        assertCode(draft, "INVALID_TURNOVER_POLICY");
    }

    @Test
    void rejectsTurnoverAboveDatabaseCeilingEvenWhenTheTimeWindowIsLonger() {
        var draft = new PricingPolicyValidator.Draft(
            "pricing-turnover-2", UUID.randomUUID(), EFFECTIVE_FROM, EFFECTIVE_TO,
            1_000_000, 7_000, 5_500, LocalTime.of(23, 0), LocalTime.of(1, 0),
            721, 15, 500, 2_000, "Create the initial pricing version", null, List.of()
        );

        assertCode(draft, "INVALID_TURNOVER_POLICY");
    }

    @Test
    void rejectsRuleOutsideProfileAndShortStayBoundaries() {
        var outside = new PricingPolicyValidator.Rule(
            "outside-range", "Outside range", EFFECTIVE_FROM.minusDays(1), EFFECTIVE_TO,
            10, List.of(), List.of(), 1, 29, null, null, "PERCENT_DISCOUNT", 500
        );
        assertCode(validDraft(List.of(outside)), "RULE_OUTSIDE_PROFILE_RANGE");

        var tooLong = rule("too-long", "PERCENT_DISCOUNT", 500, 10,
            List.of(), List.of(), 1, 30);
        assertCode(validDraft(List.of(tooLong)), "INVALID_RULE_STAY_RANGE");
    }

    @Test
    void rejectsDuplicateRuleCodes() {
        var first = rule("weekend", "PERCENT_SURCHARGE", 1_000, 10,
            List.of(6), List.of(), 1, 29);
        var second = rule("weekend", "PERCENT_SURCHARGE", 1_500, 20,
            List.of(7), List.of(), 1, 29);

        assertCode(validDraft(List.of(first, second)), "DUPLICATE_RULE_CODE");
    }

    @Test
    void rejectsAmbiguousSamePriorityFixedDailyRates() {
        var first = rule("fixed-a", "FIXED_DAILY_RATE", 800_000, 100,
            List.of(), List.of("ADMIN_PORTAL"), 1, 10);
        var second = rule("fixed-b", "FIXED_DAILY_RATE", 850_000, 100,
            List.of(6, 7), List.of(), 5, 20);

        assertCode(validDraft(List.of(first, second)), "AMBIGUOUS_FIXED_PRICE_RULES");
    }

    @Test
    void permitsSamePriorityFixedRatesWhenTheirChannelsAreDisjoint() {
        var admin = rule("admin-rate", "FIXED_DAILY_RATE", 800_000, 100,
            List.of(), List.of("ADMIN_PORTAL"), 1, 29);
        var ota = rule("ota-rate", "FIXED_DAILY_RATE", 900_000, 100,
            List.of(), List.of("OTA"), 1, 29);

        assertThatCode(() -> validator.validate(validDraft(List.of(admin, ota))))
            .doesNotThrowAnyException();
    }

    private void assertCode(PricingPolicyValidator.Draft draft, String expectedCode) {
        assertThatThrownBy(() -> validator.validate(draft))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private PricingPolicyValidator.Draft validDraft(List<PricingPolicyValidator.Rule> rules) {
        return new PricingPolicyValidator.Draft(
            "pricing-version-1", UUID.randomUUID(), EFFECTIVE_FROM, EFFECTIVE_TO,
            1_000_000, 7_000, 5_500, LocalTime.of(14, 0), LocalTime.of(12, 0),
            120, 15, 500, 2_000, "Create the initial pricing version", "pricing-test", rules
        );
    }

    private PricingPolicyValidator.Rule rule(
        String code,
        String actionType,
        long actionValue,
        int priority,
        List<Integer> weekdays,
        List<String> channels,
        Integer minimumStay,
        Integer maximumStay
    ) {
        return new PricingPolicyValidator.Rule(
            code, "Rule " + code, EFFECTIVE_FROM, EFFECTIVE_TO, priority,
            weekdays, channels, minimumStay, maximumStay, null, null, actionType, actionValue
        );
    }
}
