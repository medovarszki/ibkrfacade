package hu.auxin.ibkrfacade.session;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingSessionServiceTest {

    private static final int AAPL = 265598;
    private static final String LIQUID = "20261015:0930-20261015:1600;20261016:0930-20261016:1600;"
            + "20261017:CLOSED;20261018:CLOSED;20261019:0930-20261019:1600";
    private static final String TRADING = "20261015:0400-20261015:2000;20261016:0400-20261016:2000;"
            + "20261017:CLOSED;20261018:CLOSED;20261019:0400-20261019:2000";

    private final ContractManagerService contractManagerService = mock(ContractManagerService.class);
    private final ContractRepository contractRepository = mock(ContractRepository.class);

    @Test
    void openDuringTheRegularTradingHours() {
        TradingSession session = service("2026-10-15T15:49:00Z", holder(LIQUID, TRADING)).getSession(AAPL, false);

        assertThat(session.open()).isTrue();
        assertThat(session.hours()).isEqualTo("liquid");
        assertThat(session.timeZone()).isEqualTo("US/Eastern");
        assertThat(session.nextClose()).isEqualTo(Instant.parse("2026-10-15T20:00:00Z"));
        assertThat(session.nextOpen()).isEqualTo(Instant.parse("2026-10-16T13:30:00Z"));
        verify(contractManagerService, never()).getContractDetails(any());
    }

    @Test
    void closedAfterTheRegularHoursButOpenInTheExtendedHours() {
        TradingSessionService service = service("2026-10-15T21:00:00Z", holder(LIQUID, TRADING)); // 17:00 New York

        TradingSession regular = service.getSession(AAPL, false);
        assertThat(regular.open()).isFalse();
        assertThat(regular.nextOpen()).isEqualTo(Instant.parse("2026-10-16T13:30:00Z"));
        assertThat(regular.nextClose()).isEqualTo(Instant.parse("2026-10-16T20:00:00Z"));

        TradingSession extended = service.getSession(AAPL, true);
        assertThat(extended.hours()).isEqualTo("trading");
        assertThat(extended.open()).isTrue();
        assertThat(extended.nextClose()).isEqualTo(Instant.parse("2026-10-16T00:00:00Z"));
    }

    @Test
    void outdatedCachedHoursAreRefreshedFromTws() {
        ContractHolder cached = holder("20261001:0930-20261001:1600;20261002:0930-20261002:1600", null);
        ContractDetails fresh = details(LIQUID, TRADING);
        when(contractManagerService.getContractDetails(any(Contract.class))).thenReturn(fresh);

        TradingSession session = service("2026-10-17T15:00:00Z", cached).getSession(AAPL, false); // Saturday

        assertThat(session.open()).isFalse();
        assertThat(session.nextOpen()).isEqualTo(Instant.parse("2026-10-19T13:30:00Z"));
        assertThat(cached.getDetails()).isSameAs(fresh);
        verify(contractRepository).save(cached);
    }

    @Test
    void withoutTwsTheCachedHoursAreUsed() {
        ContractHolder cached = holder(LIQUID, TRADING);
        when(contractManagerService.getContractDetails(any(Contract.class)))
                .thenThrow(new TwsNotConnectedException("Not connected"));

        // the cached hours end on 10-19, not enough ahead on 10-18 -> refresh fails -> cached hours
        TradingSession session = service("2026-10-18T15:00:00Z", cached).getSession(AAPL, false);

        assertThat(session.open()).isFalse();
        assertThat(session.nextOpen()).isEqualTo(Instant.parse("2026-10-19T13:30:00Z"));
        verify(contractRepository, never()).save(any());
    }

    @Test
    void unknownTimeZoneIsReported() {
        ContractHolder holder = holder(LIQUID, TRADING);
        holder.getDetails().timeZoneId("Not/AZone");

        assertThatThrownBy(() -> service("2026-10-15T15:49:00Z", holder).getSession(AAPL, false))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TRADING_HOURS_UNAVAILABLE"));
    }

    private TradingSessionService service(String now, ContractHolder holder) {
        when(contractManagerService.getContractHolder(AAPL)).thenReturn(holder);
        return new TradingSessionService(contractManagerService, contractRepository,
                Clock.fixed(Instant.parse(now), ZoneOffset.UTC));
    }

    private static ContractHolder holder(String liquidHours, String tradingHours) {
        Contract contract = new Contract();
        contract.conid(AAPL);
        return new ContractHolder(contract, details(liquidHours, tradingHours));
    }

    private static ContractDetails details(String liquidHours, String tradingHours) {
        ContractDetails details = new ContractDetails();
        details.timeZoneId("US/Eastern");
        details.liquidHours(liquidHours);
        details.tradingHours(tradingHours);
        return details;
    }
}
