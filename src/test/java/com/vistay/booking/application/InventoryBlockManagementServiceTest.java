package com.vistay.booking.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryBlockManagementServiceTest {
    @Test
    void cursorRoundTripsCanonicalSortKey() {
        var updatedAt = OffsetDateTime.parse("2026-11-03T08:09:10.123456Z");
        var id = UUID.fromString("20000000-0000-0000-0000-000000000002");

        var encoded = InventoryBlockManagementService.encodeCursor(updatedAt, id);
        var decoded = InventoryBlockManagementService.decodeCursor(encoded);

        assertThat(decoded.updatedAt()).isEqualTo(updatedAt);
        assertThat(decoded.id()).isEqualTo(id);
        assertThat(encoded).doesNotContain("=", "|", "+", "/");
    }

    @Test
    void rejectsBlankMalformedAndNonCanonicalCursor() {
        assertCode("");
        assertCode("not-base64!");
        var canonical = InventoryBlockManagementService.encodeCursor(
            OffsetDateTime.parse("2026-11-03T08:09:10Z"),
            UUID.fromString("20000000-0000-0000-0000-000000000002"));
        assertCode(canonical + "=");
    }

    private void assertCode(String cursor) {
        assertThatThrownBy(() -> InventoryBlockManagementService.decodeCursor(cursor))
            .isInstanceOfSatisfying(PricingPolicyException.class,
                error -> assertThat(error.code()).isEqualTo("INVALID_INVENTORY_CURSOR"));
    }
}
