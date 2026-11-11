package com.boardgamemaster.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class PaidRequestLimitsTest {
    private static final Instant START = Instant.parse("2026-11-11T10:00:00Z");
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MutableClock clock = new MutableClock(START);

    private void stored(Object... keysAndValues) {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            rows.add(Map.of("setting_key", keysAndValues[i], "setting_value", keysAndValues[i + 1]));
        }
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenReturn(rows);
    }

    @Test
    void configuredDefaultsApplyUntilAnAdminStoresLimits() {
        stored();
        var limits = new PaidRequestLimits(20, 200, jdbc, clock);
        assertThat(limits.current()).isEqualTo(new PaidRequestLimits.Limits(20, 200, false));
        assertThat(limits.configured()).isEqualTo(new PaidRequestLimits.Limits(20, 200, false));
    }

    @Test
    void storedLimitsReplaceTheDefaultsAndAreReReadAtMostEvery30Seconds() {
        stored(PaidRequestLimits.PER_CLIENT_KEY, "60", PaidRequestLimits.PER_DAY_KEY, "0");
        var limits = new PaidRequestLimits(20, 200, jdbc, clock);
        assertThat(limits.current()).isEqualTo(new PaidRequestLimits.Limits(60, 0, true));

        // Another instance changed them: this one follows after the refresh interval, not before.
        stored(PaidRequestLimits.PER_CLIENT_KEY, "5", PaidRequestLimits.PER_DAY_KEY, "50");
        clock.now = START.plus(Duration.ofSeconds(29));
        assertThat(limits.current().perClientPerHour()).isEqualTo(60);
        verify(jdbc, times(1)).queryForList(anyString(), any(Object[].class));
        clock.now = START.plus(Duration.ofSeconds(30));
        assertThat(limits.current()).isEqualTo(new PaidRequestLimits.Limits(5, 50, true));
    }

    @Test
    void oneStoredLimitKeepsTheOtherDefaultAndUnreadableValuesAreIgnored() {
        stored(PaidRequestLimits.PER_CLIENT_KEY, "60");
        assertThat(new PaidRequestLimits(20, 200, jdbc, clock).current()).isEqualTo(new PaidRequestLimits.Limits(60, 200, true));
        stored(PaidRequestLimits.PER_CLIENT_KEY, "many", PaidRequestLimits.PER_DAY_KEY, "-4");
        assertThat(new PaidRequestLimits(20, 200, jdbc, clock).current()).isEqualTo(new PaidRequestLimits.Limits(20, 200, false));
    }

    @Test
    void anUnreachableDatabaseKeepsTheLastKnownLimits() {
        stored(PaidRequestLimits.PER_CLIENT_KEY, "60", PaidRequestLimits.PER_DAY_KEY, "600");
        var limits = new PaidRequestLimits(20, 200, jdbc, clock);
        assertThat(limits.current().perClientPerHour()).isEqualTo(60);
        when(jdbc.queryForList(anyString(), any(Object[].class))).thenThrow(new DataAccessResourceFailureException("down"));
        clock.now = START.plus(Duration.ofMinutes(5));
        assertThat(limits.current()).isEqualTo(new PaidRequestLimits.Limits(60, 600, true));
    }

    @Test
    void updateStoresBothLimitsAndAppliesThemAtOnce() {
        stored();
        var limits = new PaidRequestLimits(20, 200, jdbc, clock);
        assertThat(limits.update(100, 0)).isEqualTo(new PaidRequestLimits.Limits(100, 0, true));
        verify(jdbc).update(anyString(), eq(PaidRequestLimits.PER_CLIENT_KEY), eq("100"));
        verify(jdbc).update(anyString(), eq(PaidRequestLimits.PER_DAY_KEY), eq("0"));
        assertThat(limits.current()).isEqualTo(new PaidRequestLimits.Limits(100, 0, true));
        verify(jdbc, never()).queryForList(anyString(), any(Object[].class));
    }

    @Test
    void outOfRangeLimitsAreRejectedBeforeAnythingIsStored() {
        var limits = new PaidRequestLimits(20, 200, jdbc, clock);
        for (int[] pair : new int[][] {{-1, 10}, {10, -1}, {PaidRequestLimits.MAXIMUM + 1, 10}, {10, PaidRequestLimits.MAXIMUM + 1}}) {
            assertThatThrownBy(() -> limits.update(pair[0], pair[1])).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Use 0 for no limit");
        }
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void resetRemovesStoredLimitsAndReturnsToTheDefaults() {
        stored(PaidRequestLimits.PER_CLIENT_KEY, "60", PaidRequestLimits.PER_DAY_KEY, "600");
        var limits = new PaidRequestLimits(20, 200, jdbc, clock);
        assertThat(limits.current().custom()).isTrue();
        assertThat(limits.reset()).isEqualTo(new PaidRequestLimits.Limits(20, 200, false));
        verify(jdbc).update(anyString(), eq(PaidRequestLimits.PER_CLIENT_KEY), eq(PaidRequestLimits.PER_DAY_KEY));
    }

    @Test
    void theFilterFollowsALimitChangedWhileTheApplicationRuns() {
        stored();
        var limits = new PaidRequestLimits(1, 0, jdbc, clock);
        var filter = new PaidRequestLimitFilter(limits, clock);
        assertThat(filter.allow("a")).isTrue();
        assertThat(filter.allow("a")).as("the configured limit of 1 is reached").isFalse();
        limits.update(3, 0);
        assertThat(filter.allow("a")).as("raised to 3: the earlier question still counts").isTrue();
        assertThat(filter.allow("a")).isTrue();
        assertThat(filter.allow("a")).isFalse();
        limits.update(0, 0);
        assertThat(filter.allow("a")).as("0 turns the limit off").isTrue();
    }

    @Test
    void withoutADatabaseTheConfiguredLimitsApplyAndCannotBeChanged() {
        var limits = PaidRequestLimits.fixed(2, 0);
        assertThat(limits.current()).isEqualTo(new PaidRequestLimits.Limits(2, 0, false));
        assertThatThrownBy(() -> limits.update(5, 5)).isInstanceOf(IllegalStateException.class);
    }

    private static final class MutableClock extends Clock {
        Instant now;
        MutableClock(Instant now) { this.now = now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
