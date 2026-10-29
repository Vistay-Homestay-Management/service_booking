package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingPolicyService.RuleVersion;
import com.vistay.booking.application.PricingQuoteCalculator.Command;
import com.vistay.booking.application.PricingQuoteCalculator.PriceComponentType;
import com.vistay.booking.application.PricingQuoteCalculator.RuleEvaluation;
import com.vistay.booking.application.PricingQuoteCalculator.RuleEvaluationStatus;
import com.vistay.booking.application.PricingQuoteCalculator.StayProduct;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class PricingQuoteCalculatorTest {
    private static final UUID PROFILE_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID PROPERTY_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID ACTOR_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final LocalDate CHECK_IN = LocalDate.of(2026, 11, 2);

    private final PricingQuoteCalculator calculator = new PricingQuoteCalculator();

    @Test
    void calculatesGoldenShortStayWithStablePrecedenceAndExplanation() {
        var profile = profile("100000", 7_000, 5_500, 500, 2_000, List.of(
            rule("fixed-winning", 10, "FIXED_DAILY_RATE", "120000"),
            rule("fixed-loser", 20, "FIXED_DAILY_RATE", "130000"),
            rule("percent-discount", 30, "PERCENT_DISCOUNT", "1000"),
            rule("fixed-discount", 40, "FIXED_DISCOUNT", "5000"),
            rule("percent-surcharge", 50, "PERCENT_SURCHARGE", "500"),
            rule("weekend-only", "Weekend only", 60, List.of(6, 7), List.of(),
                null, null, null, null, "FIXED_SURCHARGE", "999")
        ));

        var quote = calculator.calculate(profile,
            command(CHECK_IN, CHECK_IN.plusDays(2), 2, "ADMIN_PORTAL", true, null));

        assertThat(quote.stayProduct()).isEqualTo(StayProduct.SHORT);
        assertThat(quote.calculationVersion()).isEqualTo("PRICING_V1");
        assertThat(quote.roundingPolicy()).isEqualTo("HALF_UP_WHOLE_VND_PER_COMPONENT");
        assertThat(quote.stayDays()).isEqualTo(2);
        assertThat(quote.baseDailyRate()).isEqualTo("100000");
        assertThat(quote.productDailyRate()).isEqualTo("100000");
        assertThat(quote.baseLodgingAmount()).isEqualTo("200000");
        assertThat(quote.lodgingBeforeAdjustments()).isEqualTo("240000");
        assertThat(quote.discountAmount()).isEqualTo("34000");
        assertThat(quote.surchargeAmount()).isEqualTo("12000");
        assertThat(quote.lodgingAmount()).isEqualTo("218000");
        assertThat(quote.commissionBps()).isEqualTo(500);
        assertThat(quote.requestedCommissionBps()).isNull();
        assertThat(quote.commissionDefaultBps()).isEqualTo(500);
        assertThat(quote.commissionMaxBps()).isEqualTo(2_000);
        assertThat(quote.commissionBaseAmount()).isEqualTo("218000");
        assertThat(quote.commissionAmount()).isEqualTo("10900");
        assertThat(quote.totalAmount()).isEqualTo("228900");
        assertThat(quote.precedence()).containsExactly(
            "FIXED_DAILY_RATE:priority-asc,code-asc,id-asc:first-match-wins",
            "ADJUSTMENTS:priority-asc,code-asc,id-asc:percentage-basis-selected-daily-rate",
            "COMMISSION:lodging-subtotal-after-rules"
        );

        assertThat(quote.evaluatedRules().subList(0, 6))
            .extracting(RuleEvaluation::code)
            .containsExactly("fixed-winning", "fixed-loser", "percent-discount",
                "fixed-discount", "percent-surcharge", "weekend-only");
        assertThat(quote.evaluatedRules().subList(0, 6))
            .extracting(RuleEvaluation::reason)
            .containsExactly("FIXED_RATE_SELECTED", "FIXED_RATE_LOWER_PRECEDENCE",
                "ACTION_APPLIED", "ACTION_APPLIED", "ACTION_APPLIED", "WEEKDAY_MISMATCH");
        assertThat(quote.evaluatedRules().subList(0, 6))
            .extracting(RuleEvaluation::adjustmentAmount)
            .containsExactly("20000", "0", "-12000", "-5000", "6000", "0");
        assertThat(quote.evaluatedRules()).filteredOn(evaluation ->
                evaluation.status() == RuleEvaluationStatus.SUPERSEDED)
            .hasSize(2);
        assertThat(quote.components()).extracting(component -> component.type())
            .containsExactly(
                PriceComponentType.FIXED_DAILY_RATE,
                PriceComponentType.RULE_DISCOUNT,
                PriceComponentType.RULE_DISCOUNT,
                PriceComponentType.RULE_SURCHARGE,
                PriceComponentType.FIXED_DAILY_RATE,
                PriceComponentType.RULE_DISCOUNT,
                PriceComponentType.RULE_DISCOUNT,
                PriceComponentType.RULE_SURCHARGE,
                PriceComponentType.COMMISSION
            );
    }

    @Test
    void explainsEveryShortStayRuleMismatch() {
        var oneDayLater = CHECK_IN.plusDays(1).toString();
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of(
            new RuleVersion(ruleId("not-effective"), "not-effective", "Not effective",
                oneDayLater, "2030-01-01", 10, List.of(), List.of(), null, null,
                null, null, "FIXED_SURCHARGE", "1"),
            rule("wrong-weekday", "Wrong weekday", 20, List.of(2), List.of(),
                null, null, null, null, "FIXED_SURCHARGE", "1"),
            rule("wrong-channel", "Wrong channel", 30, List.of(), List.of("OTA"),
                null, null, null, null, "FIXED_SURCHARGE", "1"),
            rule("wrong-stay", "Wrong stay", 40, List.of(), List.of(),
                2, 29, null, null, "FIXED_SURCHARGE", "1"),
            rule("wrong-guests", "Wrong guests", 50, List.of(), List.of(),
                null, null, 2, 10, "FIXED_SURCHARGE", "1")
        ));

        var quote = calculator.calculate(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null));
        var reasons = quote.evaluatedRules().stream().collect(Collectors.toMap(
            RuleEvaluation::code, RuleEvaluation::reason));

        assertThat(reasons).containsExactlyInAnyOrderEntriesOf(Map.of(
            "not-effective", "RULE_NOT_EFFECTIVE",
            "wrong-weekday", "WEEKDAY_MISMATCH",
            "wrong-channel", "CHANNEL_MISMATCH",
            "wrong-stay", "STAY_LENGTH_MISMATCH",
            "wrong-guests", "GUEST_COUNT_MISMATCH"
        ));
        assertThat(quote.evaluatedRules()).allMatch(evaluation ->
            evaluation.status() == RuleEvaluationStatus.SKIPPED);
    }

    @Test
    void samePriorityFixedRatesUseStableCodeTieBreaker() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of(
            rule("z-rate", 10, "FIXED_DAILY_RATE", "300"),
            rule("a-rate", 10, "FIXED_DAILY_RATE", "200")
        ));

        var quote = calculator.calculate(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null));

        assertThat(quote.lodgingAmount()).isEqualTo("200");
        assertThat(quote.evaluatedRules()).extracting(RuleEvaluation::code)
            .containsExactly("a-rate", "z-rate");
        assertThat(quote.evaluatedRules()).extracting(RuleEvaluation::status)
            .containsExactly(RuleEvaluationStatus.MATCHED, RuleEvaluationStatus.SUPERSEDED);
    }

    @Test
    void classifiesOneTwentyNineThirtyAndAnnualBoundaries() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of());
        var start = LocalDate.of(2026, 1, 1);
        record Case(LocalDate checkout, StayProduct product, int days,
                    int factor, String dailyRate, String lodging) {}
        var cases = List.of(
            new Case(start.plusDays(1), StayProduct.SHORT, 1, 10_000, "100", "100"),
            new Case(start.plusDays(29), StayProduct.SHORT, 29, 10_000, "100", "2900"),
            new Case(start.plusDays(30), StayProduct.MEDIUM, 30, 7_000, "70", "2100"),
            new Case(start.plusYears(1).minusDays(1), StayProduct.MEDIUM, 364,
                7_000, "70", "25480"),
            new Case(start.plusYears(1), StayProduct.LONG, 365, 5_500, "55", "20075")
        );

        for (var expected : cases) {
            var quote = calculator.calculate(profile,
                command(start, expected.checkout(), 1, "CUSTOMER_WEB", false, null));
            assertThat(quote.stayProduct()).as("product for %s", expected.checkout())
                .isEqualTo(expected.product());
            assertThat(quote.stayDays()).isEqualTo(expected.days());
            assertThat(quote.dailyFactorBps()).isEqualTo(expected.factor());
            assertThat(quote.productDailyRate()).isEqualTo(expected.dailyRate());
            assertThat(quote.lodgingAmount()).isEqualTo(expected.lodging());
        }
    }

    @Test
    void ignoresShortStayRulesForMediumAndLongProductsWithExplicitExplanation() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of(
            rule("fixed", 10, "FIXED_DAILY_RATE", "999"),
            rule("discount", 20, "PERCENT_DISCOUNT", "5000")
        ));
        var start = LocalDate.of(2026, 1, 1);

        for (var checkout : List.of(start.plusDays(30), start.plusYears(1))) {
            var quote = calculator.calculate(profile,
                command(start, checkout, 1, "CUSTOMER_WEB", false, null));
            assertThat(quote.discountAmount()).isEqualTo("0");
            assertThat(quote.surchargeAmount()).isEqualTo("0");
            assertThat(quote.evaluatedRules()).hasSize(2).allSatisfy(evaluation -> {
                assertThat(evaluation.status()).isEqualTo(RuleEvaluationStatus.SKIPPED);
                assertThat(evaluation.reason()).isEqualTo("STAY_PRODUCT_NOT_SHORT");
            });
            assertThat(quote.components()).singleElement().satisfies(component ->
                assertThat(component.type()).isEqualTo(PriceComponentType.STAY_PRODUCT_RATE));
        }
    }

    @Test
    void roundsFactorsAdjustmentsAndCommissionHalfUpToWholeVnd() {
        var mediumProfile = profile("1", 5_000, 5_000, 500, 2_000, List.of());
        var medium = calculator.calculate(mediumProfile,
            command(CHECK_IN, CHECK_IN.plusDays(30), 1, "CUSTOMER_WEB", false, null));
        assertThat(medium.productDailyRate()).isEqualTo("1");
        assertThat(medium.lodgingAmount()).isEqualTo("30");

        var shortProfile = profile("5", 7_000, 5_500, 1_250, 2_000, List.of(
            rule("ten-percent", 10, "PERCENT_DISCOUNT", "1000")
        ));
        var shortQuote = calculator.calculate(shortProfile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "ADMIN_PORTAL", true, null));
        assertThat(shortQuote.discountAmount()).isEqualTo("1");
        assertThat(shortQuote.lodgingAmount()).isEqualTo("4");
        assertThat(shortQuote.commissionAmount()).isEqualTo("1");
        assertThat(shortQuote.totalAmount()).isEqualTo("5");
    }

    @Test
    void capsDiscountsAtZeroAndExplainsTheCappedAction() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of(
            rule("first-discount", 10, "FIXED_DISCOUNT", "80"),
            rule("second-discount", 20, "FIXED_DISCOUNT", "80")
        ));

        var quote = calculator.calculate(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null));

        assertThat(quote.discountAmount()).isEqualTo("100");
        assertThat(quote.lodgingAmount()).isEqualTo("0");
        assertThat(quote.totalAmount()).isEqualTo("0");
        assertThat(quote.evaluatedRules()).extracting(RuleEvaluation::adjustmentAmount)
            .containsExactly("-80", "-20");
        assertThat(quote.evaluatedRules()).extracting(RuleEvaluation::afterAmount)
            .containsExactly("20", "0");
        assertThat(quote.evaluatedRules()).extracting(RuleEvaluation::reason)
            .containsExactly("ACTION_APPLIED", "ACTION_APPLIED_WITH_NON_NEGATIVE_CAP");
    }

    @Test
    void appliesDefaultCustomAndMaximumStaffCommission() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of());
        record Case(Integer requested, int effective, String amount, String total) {}
        for (var expected : List.of(
            new Case(null, 500, "5", "105"),
            new Case(1_000, 1_000, "10", "110"),
            new Case(2_000, 2_000, "20", "120")
        )) {
            var quote = calculator.calculate(profile,
                command(CHECK_IN, CHECK_IN.plusDays(1), 1, "ADMIN_PORTAL", true,
                    expected.requested()));
            assertThat(quote.commissionBps()).isEqualTo(expected.effective());
            assertThat(quote.requestedCommissionBps()).isEqualTo(expected.requested());
            assertThat(quote.commissionDefaultBps()).isEqualTo(500);
            assertThat(quote.commissionMaxBps()).isEqualTo(2_000);
            assertThat(quote.commissionBaseAmount()).isEqualTo("100");
            assertThat(quote.commissionAmount()).isEqualTo(expected.amount());
            assertThat(quote.totalAmount()).isEqualTo(expected.total());
            assertThat(quote.components().getLast().type())
                .isEqualTo(PriceComponentType.COMMISSION);
        }
    }

    @Test
    void nonStaffQuoteHasNoCommissionAndCannotRequestOne() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of());
        for (var requested : new Integer[] { null, 0 }) {
            var quote = calculator.calculate(profile,
                command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, requested));
            assertThat(quote.commissionBps()).isZero();
            assertThat(quote.commissionAmount()).isEqualTo("0");
            assertThat(quote.components()).noneMatch(component ->
                component.type() == PriceComponentType.COMMISSION);
        }

        assertCode(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, 1),
            "COMMISSION_REQUIRES_STAFF_CREATED");
    }

    @Test
    void rejectsCommissionOutsideProfileMaximumOrStaffOnExternalChannel() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of());
        assertCode(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "ADMIN_PORTAL", true, -1),
            "INVALID_COMMISSION_POLICY");
        assertCode(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "ADMIN_PORTAL", true, 2_001),
            "INVALID_COMMISSION_POLICY");
        assertCode(profile,
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "OTA", true, null),
            "INVALID_COMMISSION_POLICY");
    }

    @Test
    void rejectsProfileIdAndPropertyMismatches() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of());
        var wrongVersion = new Command(UUID.randomUUID(), PROPERTY_ID, CHECK_IN,
            CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null);
        assertCode(profile, wrongVersion, "PRICING_VERSION_MISMATCH");

        var wrongProperty = new Command(PROFILE_ID, UUID.randomUUID(), CHECK_IN,
            CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null);
        assertCode(profile, wrongProperty, "PRICING_PROFILE_PROPERTY_MISMATCH");
    }

    @Test
    void enforcesFullStayInsideProfileEffectiveRangeWithExclusiveEnd() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000,
            "2026-01-01", "2027-01-01", List.of());
        var covered = calculator.calculate(profile,
            command(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1),
                1, "CUSTOMER_WEB", false, null));
        assertThat(covered.stayDays()).isEqualTo(1);

        assertCode(profile,
            command(LocalDate.of(2025, 12, 31), LocalDate.of(2026, 1, 1),
                1, "CUSTOMER_WEB", false, null),
            "PRICING_PROFILE_NOT_EFFECTIVE");
        assertCode(profile,
            command(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 2),
                1, "CUSTOMER_WEB", false, null),
            "PRICING_PROFILE_NOT_EFFECTIVE");
    }

    @Test
    void validatesStayGuestsChannelAndStoredPolicy() {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of());
        assertCode(profile, command(CHECK_IN, CHECK_IN, 1, "CUSTOMER_WEB", false, null),
            "INVALID_STAY_RANGE");
        assertCode(profile, command(CHECK_IN, CHECK_IN.plusDays(1), 0,
            "CUSTOMER_WEB", false, null), "INVALID_GUEST_COUNT");
        assertCode(profile, command(CHECK_IN, CHECK_IN.plusDays(1), 101,
            "CUSTOMER_WEB", false, null), "INVALID_GUEST_COUNT");
        assertCode(profile, command(CHECK_IN, CHECK_IN.plusDays(1), 1,
            "PARTNER", false, null), "INVALID_PRICING_CHANNEL");
        assertCode(profile("100.50", 7_000, 5_500, 500, 2_000, List.of()),
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null),
            "INVALID_STORED_PRICING_POLICY");
        assertCode(profile("100", 7_000, 5_500, 500, 2_000,
                "invalid-date", "2030-01-01", List.of()),
            command(CHECK_IN, CHECK_IN.plusDays(1), 1, "CUSTOMER_WEB", false, null),
            "INVALID_STORED_PRICING_POLICY");
    }

    @Test
    void repeatedAndReorderedEvaluationIsDeterministic() {
        var firstRule = rule("z-surcharge", 20, "FIXED_SURCHARGE", "7");
        var secondRule = rule("a-discount", 10, "PERCENT_DISCOUNT", "1000");
        var command = command(CHECK_IN, CHECK_IN.plusDays(2), 2,
            "CUSTOMER_WEB", false, null);
        var ordered = profile("100", 7_000, 5_500, 500, 2_000,
            List.of(firstRule, secondRule));
        var reversed = profile("100", 7_000, 5_500, 500, 2_000,
            List.of(secondRule, firstRule));

        var first = calculator.calculate(ordered, command);
        var replay = calculator.calculate(ordered, command);
        var reordered = calculator.calculate(reversed, command);

        assertThat(replay).isEqualTo(first);
        assertThat(reordered).isEqualTo(first);
        assertThat(first.fingerprint()).hasSize(64);
    }

    @Test
    void calculatorIsSafeForConcurrentDeterministicReplay() throws Exception {
        var profile = profile("100", 7_000, 5_500, 500, 2_000, List.of(
            rule("discount", 10, "PERCENT_DISCOUNT", "1000"),
            rule("surcharge", 20, "FIXED_SURCHARGE", "3")
        ));
        var command = command(CHECK_IN, CHECK_IN.plusDays(3), 2,
            "CUSTOMER_WEB", false, null);
        var expected = calculator.calculate(profile, command);
        var executor = Executors.newFixedThreadPool(8);
        try {
            var calls = new ArrayList<Callable<PricingQuoteCalculator.Quote>>();
            for (int index = 0; index < 64; index++) {
                calls.add(() -> calculator.calculate(profile, command));
            }
            var futures = executor.invokeAll(calls, 20, TimeUnit.SECONDS);
            assertThat(futures).allSatisfy(future -> {
                assertThat(future.isCancelled()).isFalse();
                assertThat(future.get()).isEqualTo(expected);
            });
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void reportsOverflowInsteadOfWrappingVndAmounts() {
        var profile = profile(Long.toString(Long.MAX_VALUE), 10_000, 10_000,
            500, 2_000, List.of());

        assertCode(profile,
            command(CHECK_IN, CHECK_IN.plusDays(2), 1, "CUSTOMER_WEB", false, null),
            "PRICE_OVERFLOW");
    }

    private void assertCode(ProfileVersion profile, Command command, String expectedCode) {
        assertThatThrownBy(() -> calculator.calculate(profile, command))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo(expectedCode));
    }

    private Command command(LocalDate checkIn, LocalDate checkOut, int guests,
        String channel, boolean staffCreated, Integer commissionBps) {
        return new Command(PROFILE_ID, PROPERTY_ID, checkIn, checkOut, guests,
            channel, staffCreated, commissionBps);
    }

    private ProfileVersion profile(String baseRate, int mediumFactor, int longFactor,
        int commissionDefault, int commissionMax, List<RuleVersion> rules) {
        return profile(baseRate, mediumFactor, longFactor, commissionDefault, commissionMax,
            "2020-01-01", "2030-01-01", rules);
    }

    private ProfileVersion profile(String baseRate, int mediumFactor, int longFactor,
        int commissionDefault, int commissionMax, String effectiveFrom,
        String effectiveTo, List<RuleVersion> rules) {
        return new ProfileVersion(
            PROFILE_ID, PROPERTY_ID, 3, effectiveFrom, effectiveTo, baseRate,
            mediumFactor, longFactor, "VND", "14:00", "12:00", 120, 15,
            commissionDefault, commissionMax, "Pricing test profile", ACTOR_ID,
            "pricing-quote-test", "2026-10-30T00:00:00Z", List.copyOf(rules)
        );
    }

    private RuleVersion rule(String code, int priority, String actionType, String actionValue) {
        return rule(code, "Rule " + code, priority, List.of(), List.of(),
            null, null, null, null, actionType, actionValue);
    }

    private RuleVersion rule(String code, String name, int priority, List<Integer> daysOfWeek,
        List<String> channels, Integer minimumStay, Integer maximumStay,
        Integer minimumGuests, Integer maximumGuests, String actionType, String actionValue) {
        return new RuleVersion(
            ruleId(code), code, name, "2020-01-01", "2030-01-01", priority,
            List.copyOf(daysOfWeek), List.copyOf(channels), minimumStay, maximumStay,
            minimumGuests, maximumGuests, actionType, actionValue
        );
    }

    private UUID ruleId(String code) {
        return UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8));
    }
}
