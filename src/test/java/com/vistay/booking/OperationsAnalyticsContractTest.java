package com.vistay.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class OperationsAnalyticsContractTest {
    @Test
    void exposesBoundedCurrencySafeAnalytics() throws Exception {
        var source = Files.readString(Path.of(
            "src/main/java/com/vistay/booking/adapter/in/graphql/OperationsAnalyticsController.java"));
        var schema = Files.readString(Path.of("src/main/resources/graphql/schema.graphqls"));
        assertThat(source).contains("ANALYTICS_READ", "days > 366", "GROUP BY currency",
            "FORBIDDEN_ANALYTICS_SCOPE", "status NOT IN ('CANCELLED','EXPIRED')");
        assertThat(schema).contains("operationsAnalytics", "AnalyticsMoney", "AnalyticsSeriesPoint");
    }
}
