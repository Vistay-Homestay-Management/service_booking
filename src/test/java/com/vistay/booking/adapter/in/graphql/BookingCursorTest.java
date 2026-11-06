package com.vistay.booking.adapter.in.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.GraphqlErrorException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookingCursorTest {
    @Test
    void roundTripsStableCursor() {
        var timestamp = OffsetDateTime.parse("2026-11-06T12:34:56Z");
        var id = UUID.fromString("00000000-0000-0000-0000-000000000123");
        assertThat(BookingCursor.decode(BookingCursor.encode(timestamp, id)))
            .isEqualTo(new BookingCursor.Value(timestamp, id));
    }

    @Test
    void rejectsMalformedCursorWithStableCode() {
        assertThatThrownBy(() -> BookingCursor.decode("not-a-cursor"))
            .isInstanceOf(GraphqlErrorException.class)
            .satisfies(error -> assertThat(((GraphqlErrorException) error).getExtensions())
                .containsEntry("code", "INVALID_CURSOR"));
    }
}
