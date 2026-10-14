package hu.auxin.ibkrfacade.history;

import com.ib.client.Bar;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HistoryServiceTest {

    private static final ZoneId NEW_YORK = ZoneId.of("US/Eastern");

    @Test
    void dailyBarsAreDates() {
        HistoricalBar bar = HistoryService.toBar(bar("20261014"), NEW_YORK);
        assertThat(bar.time()).isEqualTo("2026-10-14");
        assertThat(bar.timestamp()).isEqualTo(1791950400000L); // 2026-10-14T00:00-04:00
        assertThat(bar.volume()).isEqualByComparingTo("1000");
    }

    @Test
    void intradayBarsHaveTheOffsetOfTheExchange() {
        HistoricalBar bar = HistoryService.toBar(bar("1791984600"), NEW_YORK); // 2026-10-14 13:30 UTC
        assertThat(bar.time()).isEqualTo("2026-10-14T09:30-04:00");
        assertThat(bar.timestamp()).isEqualTo(1791984600000L);
    }

    @Test
    void formattedBarTimesAreConverted() {
        HistoricalBar bar = HistoryService.toBar(bar("20261014 15:30:00 Europe/Budapest"), NEW_YORK);
        assertThat(bar.time()).isEqualTo("2026-10-14T09:30-04:00");
    }

    @Test
    void invalidParametersAreRejected() {
        assertThatThrownBy(() -> HistoryService.validate("1 X", "1 day", "TRADES", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HistoryService.validate("1 D", "7 mins", "TRADES", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HistoryService.validate("1 D", "1 day", "FOO", null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> HistoryService.validate("1 D", "1 day", "TRADES", "2026-10-14")).isInstanceOf(ApiException.class);
        HistoryService.validate("6 M", "1 hour", "MIDPOINT", "20261014-20:00:00");
    }

    @Test
    void barsAreRequestedAndCollected() {
        TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
        TwsConnection connection = mock(TwsConnection.class);
        EClientSocket client = mock(EClientSocket.class);
        when(connection.client()).thenReturn(client);
        ContractManagerService contracts = mock(ContractManagerService.class);
        Contract contract = new Contract();
        contract.conid(265598);
        contract.symbol("AAPL");
        ContractDetails details = new ContractDetails();
        details.timeZoneId("US/Eastern");
        when(contracts.getContractHolder(265598)).thenReturn(new ContractHolder(contract, details));
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            registry.addItem(id, bar("20261013"));
            registry.addItem(id, bar("20261014"));
            registry.complete(id);
            return null;
        }).when(client).reqHistoricalData(anyInt(), any(Contract.class), eq(""), eq("2 D"), eq("1 day"),
                eq("TRADES"), eq(1), eq(2), eq(false), isNull());

        HistoryResponse response = new HistoryService(connection, registry, contracts, Duration.ofSeconds(2))
                .getHistory(265598, "2 D", "1 day", "TRADES", true, null);

        assertThat(response.timeZone()).isEqualTo("US/Eastern");
        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.bars()).extracting(HistoricalBar::time).containsExactly("2026-10-13", "2026-10-14");
        assertThat(contract.exchange()).isEqualTo("SMART");
    }

    private static Bar bar(String time) {
        return new Bar(time, 1, 2, 0.5, 1.5, Decimal.get(1000), 10, Decimal.get(1.2));
    }
}
