package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.language.EnumTypeDefinition;
import graphql.language.InputObjectTypeDefinition;
import graphql.language.ObjectTypeDefinition;
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
}
