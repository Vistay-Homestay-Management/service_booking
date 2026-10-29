package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PricingGraphQlSchemaTest {
    @Test
    void exposesQuoteInputOutputExplanationAndBoundaryEnums() throws Exception {
        var schema = new ClassPathResource("graphql/schema.graphqls")
            .getContentAsString(StandardCharsets.UTF_8);
        var registry = new SchemaParser().parse(schema);

        var query = registry.getType("Query", ObjectTypeDefinition.class).orElseThrow();
        assertThat(query.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("calculatePropertyPriceQuote");

        var input = registry.getType(
            "CalculatePropertyPriceQuoteInput", InputObjectTypeDefinition.class).orElseThrow();
        assertThat(input.getInputValueDefinitions()).extracting(field -> field.getName())
            .containsExactly("rateProfileVersionId", "propertyId", "checkInDate", "checkOutDate",
                "guestCount", "channel", "staffCreated", "commissionBps");

        var quote = registry.getType("PropertyPriceQuote", ObjectTypeDefinition.class).orElseThrow();
        assertThat(quote.getFieldDefinitions()).extracting(field -> field.getName())
            .contains(
                "fingerprint", "calculationVersion", "roundingPolicy",
                "stayProduct", "stayDays", "dailyFactorBps", "productDailyRate",
                "lodgingBeforeAdjustments", "discountAmount", "surchargeAmount",
                "requestedCommissionBps", "commissionDefaultBps", "commissionMaxBps",
                "commissionBaseAmount", "commissionAmount", "totalAmount",
                "precedence", "evaluatedRules", "components"
            );

        var stayProduct = registry.getType("StayProduct", EnumTypeDefinition.class).orElseThrow();
        assertThat(stayProduct.getEnumValueDefinitions()).extracting(value -> value.getName())
            .containsExactly("SHORT", "MEDIUM", "LONG");
    }

    @Test
    void exposesManualOverrideMutationImmutableEvidenceAndApprovalScope() throws Exception {
        var schema = new ClassPathResource("graphql/schema.graphqls")
            .getContentAsString(StandardCharsets.UTF_8);
        var registry = new SchemaParser().parse(schema);

        var mutation = registry.getType("Mutation", ObjectTypeDefinition.class).orElseThrow();
        assertThat(mutation.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("applyPropertyPriceOverride");

        var input = registry.getType(
            "ApplyPropertyPriceOverrideInput", InputObjectTypeDefinition.class).orElseThrow();
        assertThat(input.getInputValueDefinitions()).extracting(field -> field.getName())
            .containsExactly("idempotencyKey", "quote", "expectedBaseQuoteFingerprint",
                "targetLodgingAmount", "reason", "correlationId");

        var result = registry.getType(
            "AppliedPropertyPriceOverride", ObjectTypeDefinition.class).orElseThrow();
        assertThat(result.getFieldDefinitions()).extracting(field -> field.getName())
            .containsExactly("baseQuote", "manualOverride", "finalPrecedence",
                "finalComponents", "finalLodgingAmount", "finalCommissionAmount",
                "finalTotalAmount");

        var evidence = registry.getType(
            "PricingManualOverride", ObjectTypeDefinition.class).orElseThrow();
        assertThat(evidence.getFieldDefinitions()).extracting(field -> field.getName())
            .contains(
                "idempotencyKey", "baseQuoteFingerprint", "calculationVersion",
                "requestHash",
                "beforeLodgingAmount", "targetLodgingAmount", "adjustmentAmount",
                "reductionBps", "beforeCommissionAmount", "afterCommissionAmount",
                "largeApprovalRequired", "actorAdminAccountId", "actorAdminProfileId",
                "authorizationVersion", "overridePermissionCode", "overrideScopeType",
                "overridePartitionId", "largeApprovalAuthorizationVersion",
                "largeApprovalPermissionCode", "largeApprovalScopeType",
                "largeApprovalPartitionId", "reason", "correlationId", "expiresAt"
            );
        assertThat(field(evidence, "authorizationVersion").getType())
            .isInstanceOfSatisfying(NonNullType.class, type ->
                assertThat(((TypeName) type.getType()).getName()).isEqualTo("String"));
        assertThat(field(evidence, "overrideScopeType").getType())
            .isInstanceOfSatisfying(NonNullType.class, type ->
                assertThat(((TypeName) type.getType()).getName())
                    .isEqualTo("PricingOverrideScopeType"));
        assertThat(field(evidence, "largeApprovalAuthorizationVersion").getType())
            .isInstanceOfSatisfying(TypeName.class, type ->
                assertThat(type.getName()).isEqualTo("String"));
        assertThat(field(evidence, "largeApprovalScopeType").getType())
            .isInstanceOfSatisfying(TypeName.class, type ->
                assertThat(type.getName()).isEqualTo("PricingOverrideScopeType"));

        var scope = registry.getType(
            "PricingOverrideScopeType", EnumTypeDefinition.class).orElseThrow();
        assertThat(scope.getEnumValueDefinitions()).extracting(value -> value.getName())
            .containsExactly("ALL", "BRANCH");
        var component = registry.getType(
            "PriceComponentType", EnumTypeDefinition.class).orElseThrow();
        assertThat(component.getEnumValueDefinitions()).extracting(value -> value.getName())
            .contains("MANUAL_OVERRIDE");
    }

    @Test
    void exposesPublicationActivePreviewAndImmutableSnapshotLifecycle() throws Exception {
        var schema = new ClassPathResource("graphql/schema.graphqls")
            .getContentAsString(StandardCharsets.UTF_8);
        var registry = new SchemaParser().parse(schema);

        var query = registry.getType("Query", ObjectTypeDefinition.class).orElseThrow();
        assertThat(query.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("previewPropertyRateProfilePublication",
                "activePropertyRateProfileVersion", "previewPublishedPropertyPriceQuote",
                "propertyPriceQuoteSnapshot");
        assertThat(field(query, "previewPropertyRateProfilePublication")
            .getInputValueDefinitions()).extracting(input -> input.getName())
            .containsExactly("rateProfileVersionId");
        var mutation = registry.getType("Mutation", ObjectTypeDefinition.class).orElseThrow();
        assertThat(mutation.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("publishPropertyRateProfileVersion",
                "createPropertyPriceQuoteSnapshot");

        var publicationInput = registry.getType(
            "PublishPropertyRateProfileVersionInput", InputObjectTypeDefinition.class)
            .orElseThrow();
        assertThat(publicationInput.getInputValueDefinitions())
            .extracting(field -> field.getName())
            .containsExactly("idempotencyKey", "rateProfileVersionId",
                "expectedProfileFingerprint", "expectedCurrentPublicationId",
                "reason", "correlationId");

        var publication = registry.getType(
            "PricingProfilePublication", ObjectTypeDefinition.class).orElseThrow();
        assertThat(publication.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("previousPublicationId", "publicationRevision",
                "snapshotSchemaVersion", "profileFingerprint", "authorizationVersion",
                "permissionCode", "scopeType", "partitionId", "publishedAt", "profile");

        var snapshotInput = registry.getType(
            "CreatePropertyPriceQuoteSnapshotInput", InputObjectTypeDefinition.class)
            .orElseThrow();
        assertThat(snapshotInput.getInputValueDefinitions())
            .extracting(field -> field.getName())
            .containsExactly("idempotencyKey", "quote", "expectedRateProfileVersionId",
                "expectedBaseQuoteFingerprint", "manualOverrideId", "reason",
                "correlationId");
        var snapshot = registry.getType(
            "PropertyPriceQuoteSnapshot", ObjectTypeDefinition.class).orElseThrow();
        assertThat(snapshot.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("pricingPublicationId", "rateProfileVersionId", "manualOverrideId",
                "baseQuoteFingerprint", "publication", "baseQuote", "finalQuote",
                "actorAdminAccountId", "authorizationVersion", "scopeType",
                "reason", "correlationId", "createdAt", "expiresAt");
    }

    @Test
    void exposesSanitizedExactUnitInventoryObservation() throws Exception {
        var schema = new ClassPathResource("graphql/schema.graphqls")
            .getContentAsString(StandardCharsets.UTF_8);
        var registry = new SchemaParser().parse(schema);

        var query = registry.getType("Query", ObjectTypeDefinition.class).orElseThrow();
        var availability = field(query, "propertyAvailability");
        assertThat(availability.getInputValueDefinitions()).extracting(input -> input.getName())
            .containsExactly("input");

        var input = registry.getType(
            "PropertyAvailabilityInput", InputObjectTypeDefinition.class).orElseThrow();
        assertThat(input.getInputValueDefinitions()).extracting(field -> field.getName())
            .containsExactly("propertyId", "checkInDate", "checkOutDate");

        var result = registry.getType(
            "PropertyAvailabilityObservation", ObjectTypeDefinition.class).orElseThrow();
        assertThat(result.getFieldDefinitions()).extracting(field -> field.getName())
            .containsExactly("propertyId", "checkInDate", "checkOutDate", "available",
                "inventoryRevision", "branchId", "propertyMembershipVersion",
                "observedAt", "overlaps");
        var overlap = registry.getType(
            "InventoryAvailabilityOverlap", ObjectTypeDefinition.class).orElseThrow();
        assertThat(overlap.getFieldDefinitions()).extracting(field -> field.getName())
            .containsExactly("type", "status", "startDate", "endDateExclusive",
                "channelProvider")
            .doesNotContain("bookingId", "externalRef", "reason", "guestName", "customerId");

        var types = registry.getType("InventoryBlockType", EnumTypeDefinition.class).orElseThrow();
        assertThat(types.getEnumValueDefinitions()).extracting(value -> value.getName())
            .containsExactly("BOOKING", "MAINTENANCE", "CLEANING", "OWNER_USE",
                "MANUAL", "CHANNEL");
        var statuses = registry.getType(
            "InventoryBlockStatus", EnumTypeDefinition.class).orElseThrow();
        assertThat(statuses.getEnumValueDefinitions()).extracting(value -> value.getName())
            .containsExactly("TENTATIVE", "ACTIVE", "RELEASED", "CANCELLED");
    }

    @Test
    void exposesExactIdempotentInventoryBlockCommandContract() throws Exception {
        var schema = new ClassPathResource("graphql/schema.graphqls")
            .getContentAsString(StandardCharsets.UTF_8);
        var registry = new SchemaParser().parse(schema);

        var mutation = registry.getType("Mutation", ObjectTypeDefinition.class).orElseThrow();
        assertThat(mutation.getFieldDefinitions()).extracting(field -> field.getName())
            .contains("createInventoryBlock", "releaseInventoryBlock");
        assertThat(field(mutation, "createInventoryBlock").getInputValueDefinitions())
            .extracting(input -> input.getName()).containsExactly("input");
        assertThat(field(mutation, "releaseInventoryBlock").getInputValueDefinitions())
            .extracting(input -> input.getName()).containsExactly("input");

        var create = registry.getType(
            "CreateInventoryBlockInput", InputObjectTypeDefinition.class).orElseThrow();
        assertThat(create.getInputValueDefinitions()).extracting(input -> input.getName())
            .containsExactly("idempotencyKey", "propertyId", "blockType", "startDate",
                "endDateExclusive", "expectedInventoryRevision", "reason",
                "correlationId");
        var release = registry.getType(
            "ReleaseInventoryBlockInput", InputObjectTypeDefinition.class).orElseThrow();
        assertThat(release.getInputValueDefinitions()).extracting(input -> input.getName())
            .containsExactly("idempotencyKey", "propertyId", "inventoryBlockId",
                "expectedInventoryRevision", "reason", "correlationId");

        var result = registry.getType(
            "InventoryBlockCommandResult", ObjectTypeDefinition.class).orElseThrow();
        assertThat(result.getFieldDefinitions()).extracting(output -> output.getName())
            .containsExactly("commandId", "idempotencyKey", "requestHash",
                "inventoryBlockId", "action", "propertyId", "blockType", "status",
                "startDate", "endDateExclusive", "blockVersion", "inventoryRevision",
                "branchId", "propertyMembershipVersion", "occurredAt", "releasedAt");
        var operatorTypes = registry.getType(
            "OperatorInventoryBlockType", EnumTypeDefinition.class).orElseThrow();
        assertThat(operatorTypes.getEnumValueDefinitions()).extracting(value -> value.getName())
            .containsExactly("MAINTENANCE", "CLEANING", "OWNER_USE", "MANUAL");
        var actions = registry.getType(
            "InventoryBlockCommandAction", EnumTypeDefinition.class).orElseThrow();
        assertThat(actions.getEnumValueDefinitions()).extracting(value -> value.getName())
            .containsExactly("CREATED", "RELEASED");
    }

    private graphql.language.FieldDefinition field(
        ObjectTypeDefinition type,
        String name
    ) {
        return type.getFieldDefinitions().stream()
            .filter(field -> name.equals(field.getName()))
            .findFirst()
            .orElseThrow();
    }
}
