package com.vistay.booking.adapter.in.graphql;

import com.vistay.booking.application.AuthorizationClient;
import com.vistay.booking.application.InventoryBlockCommandService;
import com.vistay.booking.application.InventoryBlockCommandService.AuthorizationEvidence;
import com.vistay.booking.application.InventoryBlockCommandService.CommandResult;
import com.vistay.booking.application.InventoryBlockCommandService.CreateDraft;
import com.vistay.booking.application.InventoryBlockCommandService.ReleaseDraft;
import com.vistay.booking.application.InventoryScopePolicy;
import com.vistay.booking.application.PricingPolicyException;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

@Controller
public class InventoryBlockCommandController {
    private static final String BLOCK = "inventory:block";

    private final InventoryBlockCommandService commands;
    private final AuthorizationClient authorizations;
    private final InventoryScopePolicy scopes;

    public InventoryBlockCommandController(
        InventoryBlockCommandService commands,
        AuthorizationClient authorizations,
        InventoryScopePolicy scopes
    ) {
        this.commands = commands;
        this.authorizations = authorizations;
        this.scopes = scopes;
    }

    @MutationMapping
    public CommandResult createInventoryBlock(
        @AuthenticationPrincipal Jwt jwt,
        @Argument CreateInventoryBlockInput input
    ) {
        var accountId = accountId(jwt);
        var draft = requireCreateDraft(input);
        var authorization = authorizations.require(
            accountId, BLOCK, "INVENTORY_BLOCK_FORBIDDEN");
        var scope = scopes.requireWrite(authorization, BLOCK, draft.propertyId());
        return commands.create(draft, evidence(accountId, authorization, scope));
    }

    @MutationMapping
    public CommandResult releaseInventoryBlock(
        @AuthenticationPrincipal Jwt jwt,
        @Argument ReleaseInventoryBlockInput input
    ) {
        var accountId = accountId(jwt);
        var draft = requireReleaseDraft(input);
        var authorization = authorizations.require(
            accountId, BLOCK, "INVENTORY_BLOCK_FORBIDDEN");
        var scope = scopes.requireWrite(authorization, BLOCK, draft.propertyId());
        return commands.release(draft, evidence(accountId, authorization, scope));
    }

    private static AuthorizationEvidence evidence(
        UUID accountId,
        AuthorizationClient.Snapshot authorization,
        InventoryScopePolicy.ScopeEvidence scope
    ) {
        return new AuthorizationEvidence(accountId, authorization.profileId(),
            authorization.authorizationVersion(), scope);
    }

    private static CreateDraft requireCreateDraft(CreateInventoryBlockInput input) {
        if (input == null || input.blockType() == null) throw invalidInput();
        final LocalDate start;
        final LocalDate end;
        try {
            start = LocalDate.parse(input.startDate());
            end = LocalDate.parse(input.endDateExclusive());
        } catch (DateTimeException | NullPointerException invalid) {
            throw new PricingPolicyException("INVALID_INVENTORY_RANGE",
                "Inventory dates must use ISO local-date format");
        }
        if (!end.isAfter(start)) {
            throw new PricingPolicyException("INVALID_INVENTORY_RANGE",
                "Inventory block end date must be after its start date");
        }
        return new CreateDraft(input.idempotencyKey(), canonicalUuid(input.propertyId()),
            input.blockType().name(), start, end,
            revision(input.expectedInventoryRevision()), input.reason(),
            input.correlationId());
    }

    private static ReleaseDraft requireReleaseDraft(ReleaseInventoryBlockInput input) {
        if (input == null) throw invalidInput();
        return new ReleaseDraft(input.idempotencyKey(), canonicalUuid(input.propertyId()),
            canonicalUuid(input.inventoryBlockId()),
            revision(input.expectedInventoryRevision()), input.reason(),
            input.correlationId());
    }

    private static UUID accountId(Jwt jwt) {
        if (jwt == null) {
            throw new PricingPolicyException("UNAUTHENTICATED",
                "Authentication is required");
        }
        try {
            return canonicalUuid(jwt.getSubject());
        } catch (PricingPolicyException invalid) {
            throw new PricingPolicyException("UNAUTHENTICATED",
                "Authentication is required");
        }
    }

    private static UUID canonicalUuid(String value) {
        try {
            if (value == null || !value.equals(value.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            var parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException invalid) {
            throw invalidInput();
        }
    }

    private static long revision(String value) {
        try {
            if (value == null || !value.matches("0|[1-9][0-9]*")) {
                throw new NumberFormatException("revision");
            }
            return Long.parseLong(value);
        } catch (NumberFormatException invalid) {
            throw invalidInput();
        }
    }

    private static PricingPolicyException invalidInput() {
        return new PricingPolicyException("INVALID_INVENTORY_BLOCK_INPUT",
            "Inventory block input is invalid");
    }

    public enum OperatorInventoryBlockType {
        MAINTENANCE,
        CLEANING,
        OWNER_USE,
        MANUAL
    }

    public record CreateInventoryBlockInput(
        String idempotencyKey,
        String propertyId,
        OperatorInventoryBlockType blockType,
        String startDate,
        String endDateExclusive,
        String expectedInventoryRevision,
        String reason,
        String correlationId
    ) {}

    public record ReleaseInventoryBlockInput(
        String idempotencyKey,
        String propertyId,
        String inventoryBlockId,
        String expectedInventoryRevision,
        String reason,
        String correlationId
    ) {}
}
