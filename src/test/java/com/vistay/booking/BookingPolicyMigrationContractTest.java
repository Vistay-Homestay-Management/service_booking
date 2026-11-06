package com.vistay.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BookingPolicyMigrationContractTest {
    @Test
    void definesEffectiveBindingsMakerCheckerAndEvaluationSnapshots() throws Exception {
        var sql = Files.readString(Path.of("src/main/resources/db/migration/V8_2__versioned_booking_policy_configuration.sql"));
        assertThat(sql).contains("booking_policy_versions", "booking_policy_bindings",
            "booking_policy_change_requests", "booking_policy_simulations",
            "booking_policy_evaluation_snapshots", "PROPERTY", "BRANCH", "ORGANIZATION", "GLOBAL",
            "approved_by_account_id <> created_by_account_id", "expected_policy_version");
    }
}
