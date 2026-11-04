package com.vistay.booking.application;

import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingPolicyService.RuleVersion;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PricingQuoteCalculator {
    private static final String CALCULATION_VERSION = "PRICING_V1";
    private static final String ROUNDING_POLICY = "HALF_UP_WHOLE_VND_PER_COMPONENT";
    private static final Set<String> CHANNELS = Set.of("ADMIN_PORTAL", "CUSTOMER_WEB", "OTA");
    private static final Comparator<ParsedRule> RULE_ORDER = Comparator
        .comparingInt((ParsedRule rule) -> rule.source().priority())
        .thenComparing(rule -> rule.source().code())
        .thenComparing(rule -> rule.source().id().toString());
    private static final List<String> PRECEDENCE = List.of(
        "FIXED_DAILY_RATE:priority-asc,code-asc,id-asc:first-match-wins",
        "ADJUSTMENTS:priority-asc,code-asc,id-asc:percentage-basis-selected-daily-rate",
        "COMMISSION:lodging-subtotal-after-rules"
    );

    public Quote calculate(ProfileVersion profile, Command command) {
        require(profile != null, "PRICING_VERSION_NOT_FOUND", "Pricing profile version is required");
        validate(profile, command);

        var checkIn = command.checkInDate();
        var checkOut = command.checkOutDate();
        int stayDays = Math.toIntExact(ChronoUnit.DAYS.between(checkIn, checkOut));
        var stayProduct = classify(checkIn, checkOut, stayDays);
        long baseDailyRate = amount(profile.baseDailyRate());
        var rules = profile.rules().stream().map(this::parse).sorted(RULE_ORDER).toList();
        int factorBps = switch (stayProduct) {
            case SHORT -> 10_000;
            case MEDIUM -> profile.mediumDailyFactorBps();
            case LONG -> profile.longDailyFactorBps();
        };
        long productDailyRate = roundBasisPoints(baseDailyRate, factorBps);
        long baseLodgingAmount = multiply(baseDailyRate, stayDays);

        var evaluations = new ArrayList<RuleEvaluation>();
        var components = new ArrayList<PriceComponent>();
        long lodgingBeforeAdjustments;
        long discountAmount = 0;
        long surchargeAmount = 0;
        long lodgingAmount;
        int evaluationOrder = 1;
        int componentOrder = 1;

        if (stayProduct != StayProduct.SHORT) {
            lodgingBeforeAdjustments = multiply(productDailyRate, stayDays);
            lodgingAmount = lodgingBeforeAdjustments;
            components.add(new PriceComponent(componentOrder++, PriceComponentType.STAY_PRODUCT_RATE,
                "stay-product-" + stayProduct.name().toLowerCase(), null, null, stayDays,
                text(productDailyRate), factorBps, text(lodgingBeforeAdjustments)));
            for (var rule : rules) {
                evaluations.add(evaluation(evaluationOrder++, null, rule, RuleEvaluationStatus.SKIPPED,
                    "STAY_PRODUCT_NOT_SHORT", productDailyRate, 0, productDailyRate));
            }
        } else {
            long selectedRateSubtotal = 0;
            long roomTotal = 0;
            for (var chargeDate = checkIn; chargeDate.isBefore(checkOut); chargeDate = chargeDate.plusDays(1)) {
                var dateText = chargeDate.toString();
                var matched = new ArrayList<ParsedRule>();
                var skipped = new ArrayList<RuleMatch>();
                for (var rule : rules) {
                    var skippedReason = skippedReason(rule, chargeDate, stayDays,
                        command.channel(), command.guestCount());
                    if (skippedReason == null) matched.add(rule);
                    else skipped.add(new RuleMatch(rule, skippedReason));
                }

                var fixedRates = matched.stream()
                    .filter(rule -> "FIXED_DAILY_RATE".equals(rule.source().actionType()))
                    .sorted(RULE_ORDER).toList();
                var winner = fixedRates.isEmpty() ? null : fixedRates.getFirst();
                long selectedDailyRate = winner == null ? baseDailyRate : winner.actionValue();
                long runningDailyAmount = selectedDailyRate;
                selectedRateSubtotal = add(selectedRateSubtotal, selectedDailyRate);

                if (winner == null) {
                    components.add(new PriceComponent(componentOrder++, PriceComponentType.PROFILE_BASE_RATE,
                        "profile-base", dateText, null, 1, text(baseDailyRate), 10_000,
                        text(baseDailyRate)));
                } else {
                    long adjustment = subtract(selectedDailyRate, baseDailyRate);
                    evaluations.add(evaluation(evaluationOrder++, dateText, winner,
                        RuleEvaluationStatus.MATCHED, "FIXED_RATE_SELECTED", baseDailyRate,
                        adjustment, selectedDailyRate));
                    components.add(new PriceComponent(componentOrder++, PriceComponentType.FIXED_DAILY_RATE,
                        winner.source().code(), dateText, winner.source().code(), 1,
                        text(selectedDailyRate), null, text(selectedDailyRate)));
                    for (int index = 1; index < fixedRates.size(); index++) {
                        var superseded = fixedRates.get(index);
                        evaluations.add(evaluation(evaluationOrder++, dateText, superseded,
                            RuleEvaluationStatus.SUPERSEDED, "FIXED_RATE_LOWER_PRECEDENCE",
                            selectedDailyRate, 0, selectedDailyRate));
                    }
                }

                var adjustments = matched.stream()
                    .filter(rule -> !"FIXED_DAILY_RATE".equals(rule.source().actionType()))
                    .sorted(RULE_ORDER).toList();
                for (var rule : adjustments) {
                    long before = runningDailyAmount;
                    long raw = switch (rule.source().actionType()) {
                        case "PERCENT_DISCOUNT", "PERCENT_SURCHARGE" ->
                            roundBasisPoints(selectedDailyRate, Math.toIntExact(rule.actionValue()));
                        case "FIXED_DISCOUNT", "FIXED_SURCHARGE" -> rule.actionValue();
                        default -> throw invalidStoredPolicy("Unsupported pricing action "
                            + rule.source().actionType());
                    };
                    boolean discount = rule.source().actionType().endsWith("DISCOUNT");
                    long applied = discount ? -Math.min(raw, runningDailyAmount) : raw;
                    runningDailyAmount = add(runningDailyAmount, applied);
                    if (discount) discountAmount = add(discountAmount, -applied);
                    else surchargeAmount = add(surchargeAmount, applied);
                    evaluations.add(evaluation(evaluationOrder++, dateText, rule,
                        RuleEvaluationStatus.MATCHED, discount && raw > -applied
                            ? "ACTION_APPLIED_WITH_NON_NEGATIVE_CAP" : "ACTION_APPLIED",
                        before, applied, runningDailyAmount));
                    components.add(new PriceComponent(componentOrder++, discount
                        ? PriceComponentType.RULE_DISCOUNT : PriceComponentType.RULE_SURCHARGE,
                        rule.source().code(), dateText, rule.source().code(), 1,
                        text(rule.source().actionType().startsWith("PERCENT_")
                            ? selectedDailyRate : rule.actionValue()),
                        rule.source().actionType().startsWith("PERCENT_")
                            ? Math.toIntExact(rule.actionValue()) : null,
                        text(applied)));
                }
                for (var item : skipped) {
                    evaluations.add(evaluation(evaluationOrder++, dateText, item.rule(),
                        RuleEvaluationStatus.SKIPPED, item.reason(), runningDailyAmount,
                        0, runningDailyAmount));
                }
                roomTotal = add(roomTotal, runningDailyAmount);
            }
            lodgingBeforeAdjustments = selectedRateSubtotal;
            lodgingAmount = roomTotal;
        }

        int commissionBps = effectiveCommission(profile, command);
        long commissionAmount = roundBasisPoints(lodgingAmount, commissionBps);
        if (commissionAmount > 0) {
            components.add(new PriceComponent(componentOrder, PriceComponentType.COMMISSION,
                "staff-commission", null, null, 1, text(lodgingAmount), commissionBps,
                text(commissionAmount)));
        }
        long totalAmount = add(lodgingAmount, commissionAmount);

        return new Quote(
            fingerprint(profile, command), CALCULATION_VERSION, ROUNDING_POLICY,
            profile.id(), profile.propertyId(), profile.version(),
            checkIn.toString(), checkOut.toString(), command.guestCount(), command.channel(),
            command.staffCreated(), stayProduct, stayDays, text(baseDailyRate), factorBps,
            text(productDailyRate), text(baseLodgingAmount), text(lodgingBeforeAdjustments),
            text(discountAmount), text(surchargeAmount), text(lodgingAmount), commissionBps,
            command.commissionBps(), profile.commissionDefaultBps(),
            profile.commissionMaxBps(), text(lodgingAmount), text(commissionAmount),
            text(totalAmount), profile.currency(), PRECEDENCE,
            List.copyOf(evaluations), List.copyOf(components)
        );
    }

    private void validate(ProfileVersion profile, Command command) {
        require(command != null, "INVALID_QUOTE_INPUT", "Pricing quote input is required");
        require(command.rateProfileVersionId() != null && command.propertyId() != null,
            "INVALID_QUOTE_INPUT", "Profile version and property are required");
        require(profile.id().equals(command.rateProfileVersionId()),
            "PRICING_VERSION_MISMATCH", "Loaded pricing version does not match the request");
        require(profile.propertyId().equals(command.propertyId()),
            "PRICING_PROFILE_PROPERTY_MISMATCH",
            "Pricing profile version does not belong to the requested property");
        require(command.checkInDate() != null && command.checkOutDate() != null
                && command.checkOutDate().isAfter(command.checkInDate()),
            "INVALID_STAY_RANGE", "Checkout must be after check-in");
        long days = ChronoUnit.DAYS.between(command.checkInDate(), command.checkOutDate());
        require(days <= Integer.MAX_VALUE, "INVALID_STAY_RANGE", "Stay is too long to evaluate");
        require(command.guestCount() >= 1 && command.guestCount() <= 100,
            "INVALID_GUEST_COUNT", "Guest count must be between 1 and 100");
        require(command.channel() != null && CHANNELS.contains(command.channel()),
            "INVALID_PRICING_CHANNEL", "Pricing channel is unsupported");
        require(!command.staffCreated() || "ADMIN_PORTAL".equals(command.channel()),
            "INVALID_COMMISSION_POLICY", "Staff-created pricing is available only in ADMIN_PORTAL");
        require(command.staffCreated() || command.commissionBps() == null
                || command.commissionBps() == 0,
            "COMMISSION_REQUIRES_STAFF_CREATED",
            "A non-zero commission requires a staff-created quote");

        var effectiveFrom = date(profile.effectiveFrom());
        var effectiveTo = nullableDate(profile.effectiveToExclusive());
        require(!command.checkInDate().isBefore(effectiveFrom)
                && (effectiveTo == null || !command.checkOutDate().isAfter(effectiveTo)),
            "PRICING_PROFILE_NOT_EFFECTIVE",
            "The selected pricing profile version does not cover the full stay");
    }

    private static StayProduct classify(LocalDate checkIn, LocalDate checkOut, int stayDays) {
        if (stayDays <= 29) return StayProduct.SHORT;
        return !checkOut.isBefore(checkIn.plusYears(1)) ? StayProduct.LONG : StayProduct.MEDIUM;
    }

    private static int effectiveCommission(ProfileVersion profile, Command command) {
        if (!command.staffCreated()) return 0;
        int requested = command.commissionBps() == null
            ? profile.commissionDefaultBps() : command.commissionBps();
        require(requested >= 0 && requested <= profile.commissionMaxBps(),
            "INVALID_COMMISSION_POLICY",
            "Commission must be between zero and the profile maximum");
        return requested;
    }

    private ParsedRule parse(RuleVersion rule) {
        try {
            var actionValue = Long.parseLong(rule.actionValue());
            if (actionValue <= 0) throw invalidStoredPolicy("Pricing rule value must be positive");
            return new ParsedRule(rule, date(rule.effectiveFrom()),
                nullableDate(rule.effectiveToExclusive()), actionValue);
        } catch (NumberFormatException invalid) {
            throw invalidStoredPolicy("Pricing rule contains an invalid whole VND/bps value");
        }
    }

    private static String skippedReason(ParsedRule rule, LocalDate chargeDate, int stayDays,
        String channel, int guestCount) {
        if (chargeDate.isBefore(rule.effectiveFrom())
            || (rule.effectiveToExclusive() != null
                && !chargeDate.isBefore(rule.effectiveToExclusive()))) {
            return "RULE_NOT_EFFECTIVE";
        }
        if (!rule.source().daysOfWeek().isEmpty()
            && !rule.source().daysOfWeek().contains(chargeDate.getDayOfWeek().getValue())) {
            return "WEEKDAY_MISMATCH";
        }
        if (!rule.source().channels().isEmpty() && !rule.source().channels().contains(channel)) {
            return "CHANNEL_MISMATCH";
        }
        if ((rule.source().minimumStayDays() != null
                && stayDays < rule.source().minimumStayDays())
            || (rule.source().maximumStayDays() != null
                && stayDays > rule.source().maximumStayDays())) {
            return "STAY_LENGTH_MISMATCH";
        }
        if ((rule.source().minimumGuests() != null
                && guestCount < rule.source().minimumGuests())
            || (rule.source().maximumGuests() != null
                && guestCount > rule.source().maximumGuests())) {
            return "GUEST_COUNT_MISMATCH";
        }
        return null;
    }

    private static RuleEvaluation evaluation(int order, String chargeDate, ParsedRule rule,
        RuleEvaluationStatus status, String reason, long before, long adjustment, long after) {
        return new RuleEvaluation(order, chargeDate, rule.source().id(), rule.source().code(),
            rule.source().priority(), status, reason, rule.source().actionType(),
            text(rule.actionValue()), text(before), text(adjustment), text(after));
    }

    private static long roundBasisPoints(long amount, int basisPoints) {
        require(amount >= 0 && basisPoints >= 0,
            "INVALID_STORED_PRICING_POLICY", "Pricing amounts and factors must not be negative");
        var numerator = BigInteger.valueOf(amount).multiply(BigInteger.valueOf(basisPoints));
        var rounded = numerator.add(BigInteger.valueOf(5_000)).divide(BigInteger.valueOf(10_000));
        return exactLong(rounded);
    }

    private static long multiply(long amount, int quantity) {
        return exactLong(BigInteger.valueOf(amount).multiply(BigInteger.valueOf(quantity)));
    }

    private static long add(long left, long right) {
        return exactLong(BigInteger.valueOf(left).add(BigInteger.valueOf(right)));
    }

    private static long subtract(long left, long right) {
        return exactLong(BigInteger.valueOf(left).subtract(BigInteger.valueOf(right)));
    }

    private static long exactLong(BigInteger value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new PricingPolicyException("PRICE_OVERFLOW", "Calculated pricing exceeds VND limits");
        }
    }

    private static long amount(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) throw invalidStoredPolicy("Base daily rate must be positive");
            return parsed;
        } catch (NumberFormatException invalid) {
            throw invalidStoredPolicy("Base daily rate is not a whole VND amount");
        }
    }

    private static LocalDate date(String value) {
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException invalid) {
            throw invalidStoredPolicy("Pricing profile contains an invalid date");
        }
    }

    private static LocalDate nullableDate(String value) {
        return value == null || value.isBlank() ? null : date(value);
    }

    private static PricingPolicyException invalidStoredPolicy(String message) {
        return new PricingPolicyException("INVALID_STORED_PRICING_POLICY", message);
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new PricingPolicyException(code, message);
    }

    private static String text(long value) {
        return Long.toString(value);
    }

    private static String fingerprint(ProfileVersion profile, Command command) {
        try {
            var canonical = new StringBuilder();
            append(canonical, CALCULATION_VERSION);
            append(canonical, profile.id());
            append(canonical, profile.propertyId());
            append(canonical, profile.version());
            append(canonical, profile.effectiveFrom());
            append(canonical, profile.effectiveToExclusive());
            append(canonical, profile.baseDailyRate());
            append(canonical, profile.mediumDailyFactorBps());
            append(canonical, profile.longDailyFactorBps());
            append(canonical, profile.currency());
            append(canonical, profile.commissionDefaultBps());
            append(canonical, profile.commissionMaxBps());
            append(canonical, command.propertyId());
            append(canonical, command.checkInDate());
            append(canonical, command.checkOutDate());
            append(canonical, command.guestCount());
            append(canonical, command.channel());
            append(canonical, command.staffCreated());
            append(canonical, command.commissionBps());
            profile.rules().stream()
                .sorted(Comparator.comparingInt(RuleVersion::priority)
                    .thenComparing(RuleVersion::code)
                    .thenComparing(rule -> rule.id().toString()))
                .forEach(rule -> {
                    append(canonical, rule.id());
                    append(canonical, rule.code());
                    append(canonical, rule.effectiveFrom());
                    append(canonical, rule.effectiveToExclusive());
                    append(canonical, rule.priority());
                    append(canonical, rule.daysOfWeek());
                    append(canonical, rule.channels());
                    append(canonical, rule.minimumStayDays());
                    append(canonical, rule.maximumStayDays());
                    append(canonical, rule.minimumGuests());
                    append(canonical, rule.maximumGuests());
                    append(canonical, rule.actionType());
                    append(canonical, rule.actionValue());
                });
            var digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void append(StringBuilder target, Object value) {
        var text = value == null ? "<null>" : value.toString();
        target.append(text.length()).append(':').append(text).append(';');
    }

    private record ParsedRule(RuleVersion source, LocalDate effectiveFrom,
        LocalDate effectiveToExclusive, long actionValue) {}
    private record RuleMatch(ParsedRule rule, String reason) {}

    public record Command(
        UUID rateProfileVersionId,
        UUID propertyId,
        LocalDate checkInDate,
        LocalDate checkOutDate,
        int guestCount,
        String channel,
        boolean staffCreated,
        Integer commissionBps
    ) {}

    public enum StayProduct { SHORT, MEDIUM, LONG }
    public enum RuleEvaluationStatus { MATCHED, SKIPPED, SUPERSEDED }
    public enum PriceComponentType {
        PROFILE_BASE_RATE, FIXED_DAILY_RATE, STAY_PRODUCT_RATE,
        RULE_DISCOUNT, RULE_SURCHARGE, MANUAL_OVERRIDE, COMMISSION
    }

    public record RuleEvaluation(
        int order,
        String chargeDate,
        UUID ruleId,
        String code,
        int priority,
        RuleEvaluationStatus status,
        String reason,
        String actionType,
        String actionValue,
        String beforeAmount,
        String adjustmentAmount,
        String afterAmount
    ) {}

    public record PriceComponent(
        int order,
        PriceComponentType type,
        String code,
        String chargeDate,
        String ruleCode,
        int quantity,
        String unitAmount,
        Integer factorBps,
        String amount
    ) {}

    public record Quote(
        String fingerprint,
        String calculationVersion,
        String roundingPolicy,
        UUID rateProfileVersionId,
        UUID propertyId,
        int profileVersion,
        String checkInDate,
        String checkOutDate,
        int guestCount,
        String channel,
        boolean staffCreated,
        StayProduct stayProduct,
        int stayDays,
        String baseDailyRate,
        int dailyFactorBps,
        String productDailyRate,
        String baseLodgingAmount,
        String lodgingBeforeAdjustments,
        String discountAmount,
        String surchargeAmount,
        String lodgingAmount,
        int commissionBps,
        Integer requestedCommissionBps,
        int commissionDefaultBps,
        int commissionMaxBps,
        String commissionBaseAmount,
        String commissionAmount,
        String totalAmount,
        String currency,
        List<String> precedence,
        List<RuleEvaluation> evaluatedRules,
        List<PriceComponent> components
    ) {}
}
