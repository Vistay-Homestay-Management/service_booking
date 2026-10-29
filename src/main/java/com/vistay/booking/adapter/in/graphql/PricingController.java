package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.PricingPolicyException;
import com.vistay.booking.application.PricingPolicyService;
import com.vistay.booking.application.PricingPolicyService.ProfileVersion;
import com.vistay.booking.application.PricingPolicyValidator.Draft;
import com.vistay.booking.application.PricingPolicyValidator.Rule;
import com.vistay.booking.application.PricingScopePolicy;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class PricingController {
    private final PricingPolicyService pricing;
    private final AuthorizationClient authorizations;
    private final PricingScopePolicy scopes;

    public PricingController(
        PricingPolicyService pricing,
        AuthorizationClient authorizations,
        PricingScopePolicy scopes
    ) {
        this.pricing = pricing;
        this.authorizations = authorizations;
        this.scopes = scopes;
    }

    @MutationMapping
    public ProfileVersion createPropertyRateProfileVersion(
        @AuthenticationPrincipal Jwt jwt,
        @Argument CreatePropertyRateProfileVersionInput input
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, "pricing:update");
        var draft = input.toDraft();
        scopes.require(snapshot, "pricing:update", draft.propertyId());
        return pricing.createVersion(accountId, draft);
    }

    @QueryMapping
    public ProfileVersion propertyRateProfileVersion(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID id
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, "pricing:read");
        var result = pricing.requireVersion(id);
        scopes.require(snapshot, "pricing:read", result.propertyId());
        return result;
    }

    @QueryMapping
    public List<ProfileVersion> propertyRateProfileVersions(
        @AuthenticationPrincipal Jwt jwt,
        @Argument UUID propertyId
    ) {
        var accountId = accountId(jwt);
        var snapshot = authorizations.require(accountId, "pricing:read");
        scopes.require(snapshot, "pricing:read", propertyId);
        return pricing.versions(propertyId);
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

    public record CreatePropertyRateProfileVersionInput(
        String idempotencyKey,
        UUID propertyId,
        String effectiveFrom,
        String effectiveToExclusive,
        String baseDailyRate,
        Integer mediumDailyFactorBps,
        Integer longDailyFactorBps,
        String checkInTime,
        String checkOutTime,
        Integer turnoverMinutes,
        Integer quoteTtlMinutes,
        Integer commissionDefaultBps,
        Integer commissionMaxBps,
        String reason,
        String correlationId,
        List<PricingRuleVersionInput> rules
    ) {
        Draft toDraft() {
            try {
                return new Draft(
                    idempotencyKey,
                    propertyId,
                    LocalDate.parse(effectiveFrom),
                    parseDate(effectiveToExclusive),
                    parseWholeAmount(baseDailyRate, "baseDailyRate"),
                    valueOrDefault(mediumDailyFactorBps, 7000),
                    valueOrDefault(longDailyFactorBps, 5500),
                    LocalTime.parse(valueOrDefault(checkInTime, "14:00")),
                    LocalTime.parse(valueOrDefault(checkOutTime, "12:00")),
                    valueOrDefault(turnoverMinutes, 120),
                    valueOrDefault(quoteTtlMinutes, 15),
                    valueOrDefault(commissionDefaultBps, 500),
                    valueOrDefault(commissionMaxBps, 2000),
                    reason,
                    correlationId,
                    rules == null ? List.of() : rules.stream().map(PricingRuleVersionInput::toRule).toList()
                );
            } catch (DateTimeParseException | NumberFormatException invalid) {
                throw new PricingPolicyException("INVALID_PRICING_INPUT",
                    "Dates, times, and whole VND amounts must use their documented formats");
            }
        }
    }

    public record PricingRuleVersionInput(
        String code,
        String name,
        String effectiveFrom,
        String effectiveToExclusive,
        Integer priority,
        List<Integer> daysOfWeek,
        List<PricingChannel> channels,
        Integer minimumStayDays,
        Integer maximumStayDays,
        Integer minimumGuests,
        Integer maximumGuests,
        PricingActionType actionType,
        String actionValue
    ) {
        Rule toRule() {
            try {
                return new Rule(
                    code,
                    name,
                    LocalDate.parse(effectiveFrom),
                    parseDate(effectiveToExclusive),
                    priority == null ? -1 : priority,
                    daysOfWeek == null ? List.of() : List.copyOf(daysOfWeek),
                    channels == null ? List.of() : channels.stream().map(Enum::name).toList(),
                    minimumStayDays,
                    maximumStayDays,
                    minimumGuests,
                    maximumGuests,
                    actionType == null ? null : actionType.name(),
                    parseWholeAmount(actionValue, "actionValue")
                );
            } catch (DateTimeParseException | NumberFormatException invalid) {
                throw new PricingPolicyException("INVALID_PRICING_RULE",
                    "Rule dates and values must use their documented formats");
            }
        }
    }

    public enum PricingChannel { ADMIN_PORTAL, CUSTOMER_WEB, OTA }
    public enum PricingActionType {
        FIXED_DAILY_RATE, PERCENT_DISCOUNT, FIXED_DISCOUNT, PERCENT_SURCHARGE, FIXED_SURCHARGE
    }

    private static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static long parseWholeAmount(String value, String field) {
        if (value == null || value.isBlank() || !value.matches("^[0-9]+$")) {
            throw new NumberFormatException(field);
        }
        return Long.parseLong(value);
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
