package com.vistay.booking.adapter.in.graphql;

import graphql.GraphqlErrorException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.springframework.graphql.execution.ErrorType;

final class BookingCursor {
    private BookingCursor() {}

    static String encode(OffsetDateTime createdAt, UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
            (createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    static Value decode(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            var separator = decoded.lastIndexOf('|');
            if (separator < 1) throw new IllegalArgumentException();
            return new Value(OffsetDateTime.parse(decoded.substring(0, separator)),
                UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException invalid) {
            throw GraphqlErrorException.newErrorException().message("Booking cursor is invalid")
                .errorClassification(ErrorType.BAD_REQUEST)
                .extensions(Map.of("code", "INVALID_CURSOR")).build();
        }
    }

    record Value(OffsetDateTime createdAt, UUID id) {}
}
