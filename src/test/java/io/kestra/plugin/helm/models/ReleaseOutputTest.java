package io.kestra.plugin.helm.models;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

class ReleaseOutputTest {
    @Test
    void shouldParseHelmsNanosecondUtcTimestamps() {
        assertThat(
            ReleaseOutput.instant("2026-09-18T11:47:36.453263304Z"),
            is(Instant.parse("2026-09-18T11:47:36.453263304Z"))
        );
    }

    @Test
    void shouldParseTimestampsCarryingAnOffset() {
        // Instant.parse rejects a numeric offset, which is why this goes through OffsetDateTime.
        assertThat(
            ReleaseOutput.instant("2026-01-15T10:30:00.123456789+01:00"),
            is(Instant.parse("2026-01-15T09:30:00.123456789Z"))
        );
    }

    @Test
    void shouldTreatGoZeroTimeAsAbsent() {
        // Helm 3 emits the Go zero time for a release that was never deployed; reporting a
        // year-1 date would look like real data.
        assertThat(ReleaseOutput.instant("0001-01-01T00:00:00Z"), is(nullValue()));
    }

    @Test
    void shouldReturnNullForMissingValues() {
        assertThat(ReleaseOutput.instant(null), is(nullValue()));
        assertThat(ReleaseOutput.instant(""), is(nullValue()));
        assertThat(ReleaseOutput.instant("   "), is(nullValue()));
    }

    @Test
    void shouldReturnNullRatherThanThrowOnUnparseableInput() {
        assertThat(ReleaseOutput.instant("not-a-timestamp"), is(nullValue()));
    }
}
