package hu.auxin.ibkrfacade.account;

import com.ib.client.EClientSocket;
import hu.auxin.ibkrfacade.tws.AccountMode;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
    private final TwsConnection connection = mock(TwsConnection.class);
    private final EClientSocket client = mock(EClientSocket.class);
    private final AccountService service = new AccountService(connection, registry);

    @Test
    void summaryIsParsedAndTheSubscriptionIsCancelled() {
        when(connection.client()).thenReturn(client);
        when(connection.isConnected()).thenReturn(true);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            registry.addItem(id, new AccountValue("DU1234567", "NetLiquidation", "100250.55", "USD"));
            registry.addItem(id, new AccountValue("DU1234567", "TotalCashValue", "50000", "USD"));
            registry.addItem(id, new AccountValue("DU1234567", "AvailableFunds", "80000.1", "USD"));
            registry.addItem(id, new AccountValue("DU1234567", "BuyingPower", "320000.4", "USD"));
            registry.addItem(id, new AccountValue("DU1234567", "InitMarginReq", "20000", "USD"));
            registry.addItem(id, new AccountValue("DU1234567", "MaintMarginReq", "15000", "USD"));
            registry.addItem(id, new AccountValue("DU1234567", "Cushion", "0.85", ""));
            registry.addItem(id, new AccountValue("DU7654321", "NetLiquidation", "1", "EUR"));
            registry.complete(id);
            return null;
        }).when(client).reqAccountSummary(anyInt(), eq("All"), eq(AccountService.TAGS));

        AccountSummary summary = service.getSummary(null);

        assertThat(summary.account()).isEqualTo("DU1234567");
        assertThat(summary.accountMode()).isEqualTo(AccountMode.PAPER);
        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.netLiquidation()).isEqualTo(100250.55);
        assertThat(summary.totalCashValue()).isEqualTo(50000);
        assertThat(summary.availableFunds()).isEqualTo(80000.1);
        assertThat(summary.buyingPower()).isEqualTo(320000.4);
        assertThat(summary.initMarginReq()).isEqualTo(20000);
        assertThat(summary.maintMarginReq()).isEqualTo(15000);
        assertThat(summary.cushion()).isEqualTo(0.85);
        assertThat(summary.excessLiquidity()).isNull();
        assertThat(summary.timestamp()).isNotNull();
        verify(client).cancelAccountSummary(anyInt());

        assertThat(service.getSummary("DU7654321").currency()).isEqualTo("EUR");
        assertThatThrownBy(() -> service.getSummary("U000"))
                .isInstanceOf(ApiException.class).hasMessageContaining("No account summary");
    }
}
