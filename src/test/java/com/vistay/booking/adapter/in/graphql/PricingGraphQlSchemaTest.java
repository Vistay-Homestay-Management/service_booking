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
