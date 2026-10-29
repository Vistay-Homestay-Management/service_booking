package com.vistay.booking.application;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PricingPolicyValidator {
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,160}$");
    private static final Pattern RULE_CODE = Pattern.compile("^[a-z][a-z0-9_-]{1,79}$");
    private static final Set<String> CHANNELS = Set.of("ADMIN_PORTAL", "CUSTOMER_WEB", "OTA");
    private static final Set<String> ACTIONS = Set.of(
        "FIXED_DAILY_RATE", "PERCENT_DISCOUNT", "FIXED_DISCOUNT",
        "PERCENT_SURCHARGE", "FIXED_SURCHARGE"
    );

    public void validate(Draft draft) {
        require(draft != null, "INVALID_PRICING_INPUT", "Pricing input is required");
        require(draft.propertyId() != null,
            "INVALID_PRICING_SUBJECT", "Property pricing subject is required");
        require(draft.idempotencyKey() != null
                && IDEMPOTENCY_KEY.matcher(draft.idempotencyKey()).matches(),
            "INVALID_IDEMPOTENCY_KEY", "Idempotency key must be 8-160 safe characters");
        require(validRange(draft.effectiveFrom(), draft.effectiveToExclusive()),
            "INVALID_EFFECTIVE_RANGE", "Profile effective range is invalid");
        require(draft.baseDailyRate() > 0,
            "INVALID_BASE_RATE", "Base daily rate must be a positive whole VND amount");
        require(draft.mediumDailyFactorBps() >= 1 && draft.mediumDailyFactorBps() <= 10_000,
            "INVALID_MEDIUM_FACTOR", "Medium factor must be between 1 and 10000 basis points");
        require(draft.longDailyFactorBps() >= 1
                && draft.longDailyFactorBps() <= draft.mediumDailyFactorBps(),
            "INVALID_LONG_FACTOR", "Long factor must be positive and no greater than medium factor");
        require(draft.checkInTime() != null && draft.checkOutTime() != null
                && draft.checkInTime().isAfter(draft.checkOutTime()),
            "INVALID_TURNOVER_POLICY", "Check-in must be later than checkout on the turnover day");
        long availableTurnover = ChronoUnit.MINUTES.between(draft.checkOutTime(), draft.checkInTime());
        require(draft.turnoverMinutes() >= 0 && draft.turnoverMinutes() <= 720
                && draft.turnoverMinutes() <= availableTurnover,
            "INVALID_TURNOVER_POLICY",
            "Turnover minutes must fit the checkout to check-in window and the 12-hour v1 ceiling");
        require(draft.quoteTtlMinutes() >= 1 && draft.quoteTtlMinutes() <= 1440,
            "INVALID_QUOTE_TTL", "Quote TTL must be between 1 and 1440 minutes");
        require(draft.commissionDefaultBps() >= 0
                && draft.commissionDefaultBps() <= draft.commissionMaxBps()
                && draft.commissionMaxBps() <= 2_000,
            "INVALID_COMMISSION_POLICY", "Commission must be between 0 and the 20% v1 ceiling");
        require(draft.reason() != null && draft.reason().trim().length() >= 10
                && draft.reason().trim().length() <= 1000,
            "INVALID_CHANGE_REASON", "A 10-1000 character change reason is required");
        require(draft.correlationId() == null || draft.correlationId().length() <= 100,
            "INVALID_CORRELATION_ID", "Correlation ID must not exceed 100 characters");

        var rules = draft.rules() == null ? List.<Rule>of() : draft.rules();
        Set<String> codes = new HashSet<>();
        for (var rule : rules) {
            validateRule(draft, rule);
            require(codes.add(rule.code()), "DUPLICATE_RULE_CODE",
                "Rule code must be unique within a profile version: " + rule.code());
        }
        for (int left = 0; left < rules.size(); left++) {
            for (int right = left + 1; right < rules.size(); right++) {
                rejectAmbiguousFixedPrice(rules.get(left), rules.get(right));
            }
        }
    }

    private void validateRule(Draft profile, Rule rule) {
        require(rule != null, "INVALID_PRICING_RULE", "Pricing rule is required");
        require(rule.code() != null && RULE_CODE.matcher(rule.code()).matches(),
            "INVALID_RULE_CODE", "Rule code must be lowercase and stable");
        require(rule.name() != null && !rule.name().isBlank() && rule.name().length() <= 160,
            "INVALID_RULE_NAME", "Rule name is required and must not exceed 160 characters");
        require(validRange(rule.effectiveFrom(), rule.effectiveToExclusive()),
            "INVALID_RULE_RANGE", "Rule effective range is invalid: " + rule.code());
        require(!rule.effectiveFrom().isBefore(profile.effectiveFrom())
                && (profile.effectiveToExclusive() == null
                    || (rule.effectiveToExclusive() != null
                        && !rule.effectiveToExclusive().isAfter(profile.effectiveToExclusive()))),
            "RULE_OUTSIDE_PROFILE_RANGE", "Rule range must be contained by its profile: " + rule.code());
        require(rule.priority() >= 0 && rule.priority() <= 100_000,
            "INVALID_RULE_PRIORITY", "Rule priority is out of range: " + rule.code());
        require(rule.daysOfWeek() != null && rule.daysOfWeek().stream()
                .allMatch(day -> day != null && day >= 1 && day <= 7)
                && new HashSet<>(rule.daysOfWeek()).size() == rule.daysOfWeek().size(),
            "INVALID_RULE_WEEKDAYS", "Weekdays must be unique ISO values 1-7: " + rule.code());
        require(rule.channels() != null && rule.channels().stream().allMatch(CHANNELS::contains)
                && new HashSet<>(rule.channels()).size() == rule.channels().size(),
            "INVALID_RULE_CHANNELS", "Rule contains an unsupported or duplicate channel: " + rule.code());
        require(validOptionalRange(rule.minimumStayDays(), rule.maximumStayDays(), 1, 29),
            "INVALID_RULE_STAY_RANGE", "Short-stay rule range must be within 1-29 days: " + rule.code());
        require(validOptionalRange(rule.minimumGuests(), rule.maximumGuests(), 1, 100),
            "INVALID_RULE_GUEST_RANGE", "Guest range is invalid: " + rule.code());
        require(ACTIONS.contains(rule.actionType()),
            "INVALID_RULE_ACTION", "Rule action is unsupported: " + rule.code());
        if (rule.actionType().startsWith("PERCENT_")) {
            require(rule.actionValue() >= 1 && rule.actionValue() <= 10_000,
                "INVALID_RULE_VALUE", "Percentage actions use 1-10000 basis points: " + rule.code());
        } else {
            require(rule.actionValue() > 0,
                "INVALID_RULE_VALUE", "Fixed actions require a positive whole VND amount: " + rule.code());
        }
    }

    private void rejectAmbiguousFixedPrice(Rule left, Rule right) {
        if (!"FIXED_DAILY_RATE".equals(left.actionType())
            || !"FIXED_DAILY_RATE".equals(right.actionType())
            || left.priority() != right.priority()) {
            return;
        }
        if (dateRangesOverlap(left.effectiveFrom(), left.effectiveToExclusive(),
                right.effectiveFrom(), right.effectiveToExclusive())
            && setsOverlap(left.daysOfWeek(), right.daysOfWeek())
            && setsOverlap(left.channels(), right.channels())
            && rangesOverlap(left.minimumStayDays(), left.maximumStayDays(),
                right.minimumStayDays(), right.maximumStayDays(), 1, 29)
            && rangesOverlap(left.minimumGuests(), left.maximumGuests(),
                right.minimumGuests(), right.maximumGuests(), 1, 100)) {
            throw new PricingPolicyException("AMBIGUOUS_FIXED_PRICE_RULES",
                "Same-priority fixed-price rules can overlap: " + left.code() + ", " + right.code());
        }
    }

    private static boolean validRange(LocalDate from, LocalDate toExclusive) {
        return from != null && (toExclusive == null || toExclusive.isAfter(from));
    }

    private static boolean validOptionalRange(Integer minimum, Integer maximum, int floor, int ceiling) {
        int low = minimum == null ? floor : minimum;
        int high = maximum == null ? ceiling : maximum;
        return low >= floor && high <= ceiling && low <= high;
    }

    private static boolean dateRangesOverlap(LocalDate leftFrom, LocalDate leftTo,
        LocalDate rightFrom, LocalDate rightTo) {
        return (leftTo == null || rightFrom.isBefore(leftTo))
            && (rightTo == null || leftFrom.isBefore(rightTo));
    }

    private static <T> boolean setsOverlap(List<T> left, List<T> right) {
        return left.isEmpty() || right.isEmpty() || left.stream().anyMatch(right::contains);
    }

    private static boolean rangesOverlap(Integer leftMin, Integer leftMax,
        Integer rightMin, Integer rightMax, int floor, int ceiling) {
        int lMin = leftMin == null ? floor : leftMin;
        int lMax = leftMax == null ? ceiling : leftMax;
        int rMin = rightMin == null ? floor : rightMin;
        int rMax = rightMax == null ? ceiling : rightMax;
        return lMin <= rMax && rMin <= lMax;
    }

    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new PricingPolicyException(code, message);
    }

    public record Draft(
        String idempotencyKey,
        java.util.UUID propertyId,
        LocalDate effectiveFrom,
        LocalDate effectiveToExclusive,
        long baseDailyRate,
        int mediumDailyFactorBps,
        int longDailyFactorBps,
        LocalTime checkInTime,
        LocalTime checkOutTime,
        int turnoverMinutes,
        int quoteTtlMinutes,
        int commissionDefaultBps,
        int commissionMaxBps,
        String reason,
        String correlationId,
        List<Rule> rules
    ) {}

    public record Rule(
        String code,
        String name,
        LocalDate effectiveFrom,
        LocalDate effectiveToExclusive,
        int priority,
        List<Integer> daysOfWeek,
        List<String> channels,
        Integer minimumStayDays,
        Integer maximumStayDays,
        Integer minimumGuests,
        Integer maximumGuests,
        String actionType,
        long actionValue
    ) {}
}
