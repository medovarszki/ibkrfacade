package hu.auxin.ibkrfacade.session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class TradingHoursTest {

    private static final ZoneId NEW_YORK = ZoneId.of("US/Eastern");

    /**
     * liquid_hours of AAPL as sent by TWS on 2026-10-15
     */
    private static final String AAPL = "20261015:0930-20261015:1600;20261016:0930-20261016:1600;"
            + "20261017:CLOSED;20261018:CLOSED;20261019:0930-20261019:1600";

    @Test
    void openDuringTheSession() {
        TradingHours hours = TradingHours.parse(AAPL, NEW_YORK);
        Instant now = Instant.parse("2026-10-15T15:49:00Z"); // 11:49 New York

        assertThat(hours.current(now)).hasValueSatisfying(session -> {
            assertThat(session.open()).isEqualTo(Instant.parse("2026-10-15T13:30:00Z"));
            assertThat(session.close()).isEqualTo(Instant.parse("2026-10-15T20:00:00Z"));
        });
        assertThat(hours.next(now).orElseThrow().open()).isEqualTo(Instant.parse("2026-10-16T13:30:00Z"));
        assertThat(hours.lastDate()).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    void closedOverTheWeekend() {
        TradingHours hours = TradingHours.parse(AAPL, NEW_YORK);
        Instant saturday = Instant.parse("2026-10-17T15:00:00Z");

        assertThat(hours.current(saturday)).isEmpty();
        assertThat(hours.next(saturday).orElseThrow())
                .isEqualTo(new TradingHours.Session(Instant.parse("2026-10-19T13:30:00Z"),
                        Instant.parse("2026-10-19T20:00:00Z")));
    }

    @Test
    void theCloseIsExclusive() {
        TradingHours hours = TradingHours.parse(AAPL, NEW_YORK);

        assertThat(hours.current(Instant.parse("2026-10-15T13:30:00Z"))).isPresent();
        assertThat(hours.current(Instant.parse("2026-10-15T20:00:00Z"))).isEmpty();
    }

    @Test
    void sessionsOverMidnightAndAdjacentSessionsAreMerged() {
        // futures style trading hours, with a split session
        TradingHours hours = TradingHours.parse(
                "20261014:1800-20261015:1700;20261015:1800-20261015:2359;20261015:2359-20261016:1700",
                NEW_YORK);

        assertThat(hours.sessions()).hasSize(2);
        assertThat(hours.current(Instant.parse("2026-10-16T04:00:00Z")).orElseThrow().close())
                .isEqualTo(Instant.parse("2026-10-16T21:00:00Z"));
    }

    @Test
    void olderFormatWithoutEndDate() {
        TradingHours hours = TradingHours.parse("20261015:0930-1600,1700-0200;20261016:CLOSED", NEW_YORK);

        assertThat(hours.sessions()).extracting(TradingHours.Session::close).containsExactly(
                Instant.parse("2026-10-15T20:00:00Z"), Instant.parse("2026-10-16T06:00:00Z"));
        assertThat(hours.lastDate()).isEqualTo(LocalDate.of(2026, 10, 16));
    }

    @Test
    void malformedEntriesAreSkipped() {
        TradingHours hours = TradingHours.parse("garbage;2026101:0930-1600;20261015:0930-20261015:1600;", NEW_YORK);

        assertThat(hours.sessions()).hasSize(1);
        assertThat(TradingHours.parse(null, NEW_YORK).sessions()).isEmpty();
        assertThat(TradingHours.parse("", NEW_YORK).lastDate()).isNull();
    }
}
