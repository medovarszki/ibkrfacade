package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.TickType;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.marketdata.MarketDataType;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import redis.clients.jedis.timeseries.TSElement;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GET /price: live price, stale price, and a request without market data subscription (TWS client mocked).
 */
class ContractManagerServicePriceTest {

    private static final int AAPL = 265598;

    private final TwsConnection connection = mock(TwsConnection.class);
    private final EClientSocket client = mock(EClientSocket.class);
    private final TimeSeriesHandler timeSeries = mock(TimeSeriesHandler.class);
    private final ContractRepository contractRepository = mock(ContractRepository.class);
    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
    private final MarketDataService marketDataService = new MarketDataService(connection, registry, timeSeries, 1,
            false, Duration.ofMinutes(15));
    private final ContractManagerService service = new ContractManagerService(connection, registry,
            contractRepository, marketDataService, Duration.ofMillis(1500));

    /**
     * Fake RedisTimeSeries: latest data point by key
     */
    private final Map<String, TSElement> redis = new ConcurrentHashMap<>();
    private final Contract contract = new Contract();

    @BeforeEach
    void setUp() {
        contract.conid(AAPL);
        contract.symbol("AAPL");
        when(contractRepository.findById(AAPL)).thenReturn(Optional.of(new ContractHolder(contract)));
        when(connection.client()).thenReturn(client);
        when(timeSeries.latest(anyInt(), any())).thenAnswer(invocation ->
                redis.get(TimeSeriesHandler.key(invocation.getArgument(0), invocation.getArgument(1))));
        when(timeSeries.addToStream(anyInt(), anyDouble(), any())).thenAnswer(invocation -> {
            long now = System.currentTimeMillis();
            redis.put(TimeSeriesHandler.key(invocation.getArgument(0), invocation.getArgument(2)),
                    new TSElement(now, invocation.getArgument(1)));
            return now;
        });
    }

    @Test
    void livePrice() {
        int tickerId = marketDataService.subscribe(contract);
        marketDataService.onMarketDataType(tickerId, 1);
        marketDataService.onTick(tickerId, TickType.BID.index(), 345.0);
        marketDataService.onTick(tickerId, TickType.ASK.index(), 345.01);

        MarketPrice price = service.getLastPriceByConid(AAPL, Duration.ofSeconds(60));

        assertThat(price.bid()).isEqualTo(345.0);
        assertThat(price.ask()).isEqualTo(345.01);
        assertThat(price.subscribed()).isTrue();
        assertThat(price.stale()).isFalse();
        assertThat(price.quoteAgeSeconds()).isBetween(0L, 1L);
        assertThat(price.marketDataType()).isEqualTo(MarketDataType.LIVE);
    }

    @Test
    void stalePrice() {
        int tickerId = marketDataService.subscribe(contract);
        marketDataService.onMarketDataType(tickerId, 2);
        storeQuote(Instant.now().minus(Duration.ofHours(20)), 345.0, 345.01);

        MarketPrice price = service.getLastPriceByConid(AAPL);

        assertThat(price.bid()).isEqualTo(345.0);
        assertThat(price.stale()).isTrue();
        assertThat(price.quoteAgeSeconds()).isBetween(20 * 3600L, 20 * 3600L + 1);
        assertThat(price.marketDataType()).isEqualTo(MarketDataType.FROZEN);
        assertThatThrownBy(() -> service.getLastPriceByConid(AAPL, Duration.ofMinutes(15)))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
                    assertThat(e.getCode()).isEqualTo("STALE_MARKET_DATA");
                    assertThat(e.getMessage()).contains("the latest quote is from");
                });
    }

    /**
     * The incident of 2026-10-15: yesterday's quote in Redis, no subscription after a restart of the facade.
     */
    @Test
    void withoutSubscriptionItSubscribesAndWaitsForTheFirstQuote() {
        storeQuote(Instant.now().minus(Duration.ofHours(20)), 345.0, 345.01);
        doAnswer(invocation -> {
            int tickerId = invocation.getArgument(0);
            CompletableFuture.runAsync(() -> {
                marketDataService.onMarketDataType(tickerId, 3);
                marketDataService.onTick(tickerId, TickType.DELAYED_BID.index(), 351.2);
                marketDataService.onTick(tickerId, TickType.DELAYED_ASK.index(), 351.25);
            }, CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS));
            return null;
        }).when(client).reqMktData(anyInt(), any(), eq(""), eq(false), eq(false), any());

        MarketPrice price = service.getLastPriceByConid(AAPL);

        assertThat(price.subscribed()).isTrue();
        assertThat(price.bid()).isEqualTo(351.2);
        assertThat(price.ask()).isEqualTo(351.25);
        assertThat(price.stale()).isFalse();
        assertThat(price.marketDataType()).isEqualTo(MarketDataType.DELAYED);
        verify(client).reqMktData(anyInt(), any(), eq(""), eq(false), eq(false), any());
    }

    @Test
    void withoutSubscriptionAndNoQuoteInTimeTheOldPriceIsMarkedStale() {
        storeQuote(Instant.now().minus(Duration.ofHours(20)), 345.0, 345.01);

        long start = System.nanoTime();
        MarketPrice price = service.getLastPriceByConid(AAPL);

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1500));
        assertThat(price.subscribed()).isTrue();
        assertThat(price.bid()).isEqualTo(345.0);
        assertThat(price.stale()).isTrue();
    }

    @Test
    void withoutTwsConnectionTheCachedPriceIsMarkedStale() {
        when(connection.client()).thenThrow(new TwsNotConnectedException("Not connected"));
        storeQuote(Instant.now().minus(Duration.ofHours(20)), 345.0, 345.01);

        MarketPrice price = service.getLastPriceByConid(AAPL);

        assertThat(price.subscribed()).isFalse();
        assertThat(price.bid()).isEqualTo(345.0);
        assertThat(price.stale()).isTrue();
        verify(client, never()).reqMktData(anyInt(), any(), any(), eq(false), eq(false), any());
    }

    @Test
    void noQuoteAtAllIsStale() {
        marketDataService.subscribe(contract);

        MarketPrice price = service.getLastPriceByConid(AAPL);

        assertThat(price.quoteTime()).isNull();
        assertThat(price.quoteAgeSeconds()).isNull();
        assertThat(price.stale()).isTrue();
        assertThat(price.marketDataType()).isNull();
    }

    private void storeQuote(Instant time, double bid, double ask) {
        redis.put(TimeSeriesHandler.key(AAPL, TickType.BID), new TSElement(time.toEpochMilli(), bid));
        redis.put(TimeSeriesHandler.key(AAPL, TickType.ASK), new TSElement(time.toEpochMilli(), ask));
    }
}
