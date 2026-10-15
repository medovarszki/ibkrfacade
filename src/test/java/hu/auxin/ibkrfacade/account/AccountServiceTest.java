package hu.auxin.ibkrfacade.account;

import com.ib.client.EClientSocket;
import hu.auxin.ibkrfacade.tws.AccountMode;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.news.NewsService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import hu.auxin.ibkrfacade.tws.TwsCallbackHandler;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.tws.TwsException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountServiceTest {

    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
    private final TwsConnection connection = mock(TwsConnection.class);
    private final EClientSocket client = mock(EClientSocket.class);
    private final AccountService service = new AccountService(connection, registry, Duration.ofSeconds(2));
    private final TwsCallbackHandler handler = new TwsCallbackHandler(connection, registry,
            mock(MarketDataService.class), mock(OrderManagerService.class), mock(PositionManagerService.class),
            mock(NewsService.class), service);

    @Test
    void summaryIsParsedFromThePersistentSubscription() {
        when(connection.client()).thenReturn(client);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            handler.accountSummary(id, "DU1234567", "NetLiquidation", "100250.55", "USD");
            handler.accountSummary(id, "DU1234567", "TotalCashValue", "50000", "USD");
            handler.accountSummary(id, "DU1234567", "AvailableFunds", "80000.1", "USD");
            handler.accountSummary(id, "DU1234567", "BuyingPower", "320000.4", "USD");
            handler.accountSummary(id, "DU1234567", "InitMarginReq", "20000", "USD");
            handler.accountSummary(id, "DU1234567", "MaintMarginReq", "15000", "USD");
            handler.accountSummary(id, "DU1234567", "Cushion", "0.85", "");
            handler.accountSummary(id, "DU7654321", "NetLiquidation", "1", "EUR");
            handler.accountSummaryEnd(id);
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
        assertThat(summary.updatedAt()).isNotNull().isBeforeOrEqualTo(summary.timestamp());

        assertThat(service.getSummary("DU7654321").currency()).isEqualTo("EUR");
        assertThatThrownBy(() -> service.getSummary("U000"))
                .isInstanceOf(ApiException.class).hasMessageContaining("No account summary");
        verify(client, times(1)).reqAccountSummary(anyInt(), anyString(), anyString());
        verify(client, never()).cancelAccountSummary(anyInt());
    }

    @Test
    void updatesSentByTwsAreReturned() {
        AtomicInteger requestId = new AtomicInteger();
        when(connection.client()).thenReturn(client);
        doAnswer(invocation -> {
            requestId.set(invocation.getArgument(0));
            handler.accountSummary(requestId.get(), "DU1234567", "NetLiquidation", "100", "USD");
            handler.accountSummaryEnd(requestId.get());
            return null;
        }).when(client).reqAccountSummary(anyInt(), anyString(), anyString());
        assertThat(service.getSummary(null).netLiquidation()).isEqualTo(100);

        handler.accountSummary(requestId.get(), "DU1234567", "NetLiquidation", "105", "USD");

        assertThat(service.getSummary(null).netLiquidation()).isEqualTo(105);
    }

    @Test
    void aNewTwsSessionIsSubscribedAgain() {
        EClientSocket reconnected = mock(EClientSocket.class);
        when(connection.client()).thenReturn(client);
        answerWithNav(client, "100");
        answerWithNav(reconnected, "200");
        service.onConnected(new TwsConnectedEvent(false));
        assertThat(service.getSummary(null).netLiquidation()).isEqualTo(100);

        when(connection.client()).thenReturn(reconnected);
        assertThat(service.getSummary(null).netLiquidation()).isEqualTo(200);
        verify(client, times(1)).reqAccountSummary(anyInt(), anyString(), anyString());
        verify(reconnected, times(1)).reqAccountSummary(anyInt(), anyString(), anyString());
    }

    @Test
    void thePreviousSubscriptionIsKeptIfTwsRejectsTheNewOne() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger firstId = new AtomicInteger();
        when(connection.client()).thenReturn(client);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                firstId.set(id);
                handler.accountSummary(id, "DU1234567", "NetLiquidation", "100", "USD");
                handler.accountSummaryEnd(id);
            } else {
                handler.error(id, 0L, 322, "Maximum number of account summary requests exceeded", null);
            }
            return null;
        }).when(client).reqAccountSummary(anyInt(), anyString(), anyString());
        service.onConnected(new TwsConnectedEvent(false));

        service.onConnected(new TwsConnectedEvent(true)); // 1101, but TWS kept the subscription
        handler.accountSummary(firstId.get(), "DU1234567", "NetLiquidation", "110", "USD");

        assertThat(service.getSummary(null).netLiquidation()).isEqualTo(110);
    }

    @Test
    void aFailedSubscriptionIsReportedAndRetriedOnTheNextCall() {
        AtomicInteger calls = new AtomicInteger();
        when(connection.client()).thenReturn(client);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (calls.getAndIncrement() == 0) {
                handler.error(id, 0L, 321, "Error validating request", null);
            } else {
                handler.accountSummary(id, "DU1234567", "NetLiquidation", "100", "USD");
                handler.accountSummaryEnd(id);
            }
            return null;
        }).when(client).reqAccountSummary(anyInt(), anyString(), anyString());

        assertThatThrownBy(() -> service.getSummary(null))
                .isInstanceOf(TwsException.class).hasMessageContaining("Error validating request");
        assertThat(service.getSummary(null).netLiquidation()).isEqualTo(100);
    }

    private void answerWithNav(EClientSocket socket, String nav) {
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            handler.accountSummary(id, "DU1234567", "NetLiquidation", nav, "USD");
            handler.accountSummaryEnd(id);
            return null;
        }).when(socket).reqAccountSummary(anyInt(), anyString(), anyString());
    }

    /**
     * IB Gateway 10.50 (server version 226) ignores the protobuf encoded cancelAccountSummary, so a request-cancel
     * cycle per call exhausts the limit of two active account summary subscriptions with error 322 on the third call.
     */
    @Test
    void repeatedCallsDoNotExhaustTheAccountSummaryLimitWhenTwsIgnoresTheCancel() {
        Set<Integer> active = ConcurrentHashMap.newKeySet();
        when(connection.client()).thenReturn(client);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            if (active.size() >= 2) {
                handler.error(id, 0L, 322, "Error processing request.-'Q' : cause - Maximum number of account "
                        + "summary requests exceeded; desubscribe to previous request first", null);
                return null;
            }
            active.add(id);
            handler.accountSummary(id, "DU1234567", "NetLiquidation", "100250.55", "USD");
            handler.accountSummaryEnd(id);
            return null;
        }).when(client).reqAccountSummary(anyInt(), eq("All"), eq(AccountService.TAGS));

        for (int i = 0; i < 5; i++) {
            assertThat(service.getSummary(null).netLiquidation()).isEqualTo(100250.55);
        }
        verify(client, times(1)).reqAccountSummary(anyInt(), anyString(), anyString());
    }
}
