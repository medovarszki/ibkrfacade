package hu.auxin.ibkrfacade.marketdata;

import com.ib.client.ComboLeg;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.TickType;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsErrorCodes;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.timeseries.TSElement;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Futures curve features: sizes and statistics of the quotes, index prices, prices of several instruments, the
 * futures chain and calendar spreads (TWS client mocked).
 */
class FuturesMarketDataTest {

    private static final int VIX = 13455763;
    private static final int NOV = 1001;
    private static final int DEC = 1002;

    private final TwsConnection connection = mock(TwsConnection.class);
    private final EClientSocket client = mock(EClientSocket.class);
    private final TimeSeriesHandler timeSeries = mock(TimeSeriesHandler.class);
    private final ContractRepository contractRepository = mock(ContractRepository.class);
    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
    private final MarketDataService marketDataService = new MarketDataService(connection, registry, timeSeries, 1,
            false, Duration.ofMinutes(15));
    private final ContractManagerService contracts = new ContractManagerService(connection, registry,
            contractRepository, marketDataService, Duration.ofMillis(300));
    private final ComboMarketDataService combos = new ComboMarketDataService(connection, registry, 2,
            Duration.ofMinutes(15), Duration.ofMillis(200));
    private final SpreadService spreads = new SpreadService(contracts, combos);

    private final Map<String, TSElement> redis = new ConcurrentHashMap<>();
    private final Contract nov = future(NOV, "VXX6", "20261118");
    private final Contract dec = future(DEC, "VXZ6", "20261216");

    @BeforeEach
    void setUp() {
        when(connection.client()).thenReturn(client);
        when(contractRepository.findById(NOV)).thenReturn(Optional.of(new ContractHolder(nov)));
        when(contractRepository.findById(DEC)).thenReturn(Optional.of(new ContractHolder(dec)));
        when(contractRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
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
    void futuresQuoteHasSizesVolumeOpenInterestAndSettlement() {
        int tickerId = marketDataService.subscribe(nov);
        quote(tickerId, 17.05, 17.10);
        marketDataService.onTick(tickerId, TickType.LAST.index(), 17.10);
        marketDataService.onTick(tickerId, TickType.CLOSE.index(), 17.35);
        marketDataService.onTickSize(tickerId, TickType.BID_SIZE.index(), Decimal.get(142));
        marketDataService.onTickSize(tickerId, TickType.ASK_SIZE.index(), Decimal.get(98));
        marketDataService.onTickSize(tickerId, TickType.VOLUME.index(), Decimal.get(48210));
        marketDataService.onTickSize(tickerId, TickType.FUTURES_OPEN_INTEREST.index(), Decimal.get(96400));

        MarketPrice price = marketDataService.latestPrice(NOV);

        assertThat(price.bidSize()).isEqualByComparingTo("142");
        assertThat(price.askSize()).isEqualByComparingTo("98");
        assertThat(price.volume()).isEqualByComparingTo("48210");
        assertThat(price.openInterest()).isEqualByComparingTo("96400");
        assertThat(price.close()).isEqualTo(17.35);
        assertThat(price.mid()).isEqualTo(17.075);
        assertThat(price.spread()).isEqualTo(0.05);
        assertThat(price.quoteBasis()).isEqualTo(MarketPrice.QuoteBasis.BID_ASK);
        // the open interest of futures is a generic tick
        verify(client).reqMktData(eq(tickerId), eq(nov), eq("588"), eq(false), eq(false), any());
    }

    @Test
    void indexIsFreshByItsLastValue() {
        Contract index = new Contract();
        index.conid(VIX);
        index.symbol("VIX");
        index.secType(Types.SecType.IND);
        int tickerId = marketDataService.subscribe(index);
        marketDataService.onTick(tickerId, TickType.LAST.index(), 16.42);
        marketDataService.onTick(tickerId, TickType.CLOSE.index(), 16.90);

        MarketPrice price = marketDataService.latestPrice(VIX);

        assertThat(price.bid()).isNull();
        assertThat(price.last()).isEqualTo(16.42);
        assertThat(price.close()).isEqualTo(16.90);
        assertThat(price.quoteBasis()).isEqualTo(MarketPrice.QuoteBasis.LAST);
        assertThat(price.stale()).isFalse();
        assertThat(price.quoteAgeSeconds()).isBetween(0L, 1L);
        assertThat(price.hasQuote(Duration.ofSeconds(60), java.time.Instant.now())).isTrue();
    }

    @Test
    void pricesOfSeveralInstrumentsSubscribeTheMissingOnes() {
        quote(marketDataService.subscribe(nov), 17.05, 17.10);
        doAnswer(invocation -> {
            quote(invocation.getArgument(0), 18.35, 18.40);
            return null;
        }).when(client).reqMktData(anyInt(), eq(dec), any(), eq(false), eq(false), any());

        List<MarketPrice> prices = contracts.getLastPrices(List.of(DEC, NOV));

        assertThat(prices).extracting(MarketPrice::conid).containsExactly(DEC, NOV);
        assertThat(prices).extracting(MarketPrice::bid).containsExactly(18.35, 17.05);
        assertThat(prices).allMatch(MarketPrice::subscribed);
    }

    @Test
    void pricesWithoutTwsConnectionAreTheCachedOnes() {
        when(connection.client()).thenThrow(new TwsNotConnectedException("Not connected"));

        List<MarketPrice> prices = contracts.getLastPrices(List.of(NOV, DEC));

        assertThat(prices).hasSize(2).noneMatch(MarketPrice::subscribed).allMatch(MarketPrice::stale);
    }

    @Test
    void futuresChainIsOrderedByExpirationAndSaved() {
        when(contractRepository.findById(anyInt())).thenReturn(Optional.empty());
        ArgumentCaptor<Contract> query = ArgumentCaptor.forClass(Contract.class);
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            registry.addItem(id, details(dec));
            registry.addItem(id, details(future(2001, "VX46X6", "20261111", "VX46")));
            registry.addItem(id, details(nov));
            registry.complete(id);
            return null;
        }).when(client).reqContractDetails(anyInt(), query.capture());

        List<ContractHolder> chain = contracts.getFuturesChain("VIX", "CFE", "USD", "VX", true);

        assertThat(chain).extracting(ContractHolder::getConid).containsExactly(NOV, DEC);
        assertThat(query.getValue().secType()).isEqualTo(Types.SecType.FUT);
        assertThat(query.getValue().tradingClass()).isEqualTo("VX");
        assertThat(query.getValue().includeExpired()).isTrue();
        verify(contractRepository, times(2)).save(any());
    }

    @Test
    void unknownFuturesChainIsNotFound() {
        doAnswer(invocation -> {
            registry.fail(invocation.getArgument(0), new TwsException(TwsErrorCodes.NO_SECURITY_DEFINITION, "No security definition"));
            return null;
        }).when(client).reqContractDetails(anyInt(), any());

        assertThatThrownBy(() -> contracts.getFuturesChain("FOO", "CFE", null, null, false))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("CONTRACT_NOT_FOUND"));
    }

    @Test
    void spreadFromTheLegsAndFromTheExchangeListedCombo() {
        quote(marketDataService.subscribe(nov), 17.05, 17.10);
        int decTicker = marketDataService.subscribe(dec);
        quote(decTicker, 18.35, 18.40);
        marketDataService.onTick(marketDataService.subscribe(nov), TickType.CLOSE.index(), 17.35);
        marketDataService.onTick(decTicker, TickType.CLOSE.index(), 18.55);
        ArgumentCaptor<Contract> bag = ArgumentCaptor.forClass(Contract.class);
        doAnswer(invocation -> {
            int tickerId = invocation.getArgument(0);
            combos.onTick(tickerId, TickType.BID.index(), 1.29);
            combos.onTickSize(tickerId, TickType.BID_SIZE.index(), Decimal.get(85));
            combos.onTick(tickerId, TickType.ASK.index(), 1.31);
            combos.onTickSize(tickerId, TickType.ASK_SIZE.index(), Decimal.get(60));
            return null;
        }).when(client).reqMktData(anyInt(), bag.capture(), eq(""), eq(false), eq(false), any());

        SpreadQuote spread = spreads.getSpread(NOV, DEC);

        assertThat(spread.synthetic().ask()).isEqualTo(1.35); // buy: far ask - near bid
        assertThat(spread.synthetic().bid()).isEqualTo(1.25); // sell: far bid - near ask
        assertThat(spread.synthetic().mid()).isEqualTo(1.3);
        assertThat(spread.synthetic().close()).isEqualTo(1.2);
        assertThat(spread.synthetic().stale()).isFalse();
        assertThat(spread.nativeQuote().bid()).isEqualTo(1.29);
        assertThat(spread.nativeQuote().ask()).isEqualTo(1.31);
        assertThat(spread.nativeQuote().mid()).isEqualTo(1.3);
        assertThat(spread.nativeQuote().bidSize()).isEqualByComparingTo("85");
        assertThat(spread.nativeQuote().subscribed()).isTrue();
        assertThat(spread.nativeQuote().stale()).isFalse();
        assertThat(bag.getValue().secType()).isEqualTo(Types.SecType.BAG);
        assertThat(bag.getValue().comboLegs()).extracting(ComboLeg::conid, leg -> leg.action().getApiString())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DEC, "BUY"),
                        org.assertj.core.groups.Tuple.tuple(NOV, "SELL"));
    }

    @Test
    void negativeSpreadIsAQuoteButMinusOneWithoutSizeIsNot() {
        doAnswer(invocation -> {
            int tickerId = invocation.getArgument(0);
            combos.onTick(tickerId, TickType.BID.index(), -0.45);
            combos.onTickSize(tickerId, TickType.BID_SIZE.index(), Decimal.get(10));
            combos.onTick(tickerId, TickType.ASK.index(), -1);
            combos.onTickSize(tickerId, TickType.ASK_SIZE.index(), Decimal.get(0));
            return null;
        }).when(client).reqMktData(anyInt(), any(), eq(""), eq(false), eq(false), any());

        SpreadQuote.Native quote = combos.quote(nov, dec);

        assertThat(quote.bid()).isEqualTo(-0.45);
        assertThat(quote.ask()).isNull();
        assertThat(quote.mid()).isNull();
    }

    @Test
    void onlyTheMostRecentSpreadsStaySubscribed() {
        Contract jan = future(1003, "VXF7", "20270120");
        combos.quote(nov, dec);
        combos.quote(dec, jan);
        combos.quote(nov, dec); // most recently used
        combos.quote(nov, jan); // evicts dec-jan

        verify(client, times(1)).cancelMktData(anyInt());
        combos.quote(nov, dec);
        verify(client, times(3)).reqMktData(anyInt(), any(), eq(""), eq(false), eq(false), any());
    }

    @Test
    void spreadWithoutTwsConnectionHasNoNativeQuote() {
        when(connection.client()).thenThrow(new TwsNotConnectedException("Not connected"));

        SpreadQuote spread = spreads.getSpread(NOV, DEC);

        assertThat(spread.nativeQuote().subscribed()).isFalse();
        assertThat(spread.nativeQuote().streamError()).contains("not connected");
        assertThat(spread.synthetic().stale()).isTrue();
    }

    @Test
    void spreadLegsMustBeDifferentFuturesOfTheSameUnderlying() {
        Contract stock = new Contract();
        stock.conid(265598);
        stock.secType(Types.SecType.STK);
        when(contractRepository.findById(265598)).thenReturn(Optional.of(new ContractHolder(stock)));

        assertThatThrownBy(() -> spreads.getSpread(NOV, NOV))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_SPREAD"));
        assertThatThrownBy(() -> spreads.getSpread(NOV, 265598))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_SPREAD"));
    }

    private void quote(int tickerId, double bid, double ask) {
        marketDataService.onTick(tickerId, TickType.BID.index(), bid);
        marketDataService.onTick(tickerId, TickType.ASK.index(), ask);
    }

    private static Contract future(int conid, String localSymbol, String lastTradeDate) {
        return future(conid, localSymbol, lastTradeDate, "VX");
    }

    private static Contract future(int conid, String localSymbol, String lastTradeDate, String tradingClass) {
        Contract contract = new Contract();
        contract.conid(conid);
        contract.symbol("VIX");
        contract.secType(Types.SecType.FUT);
        contract.exchange("CFE");
        contract.currency("USD");
        contract.localSymbol(localSymbol);
        contract.tradingClass(tradingClass);
        contract.lastTradeDateOrContractMonth(lastTradeDate);
        return contract;
    }

    private static ContractDetails details(Contract contract) {
        ContractDetails details = new ContractDetails();
        details.contract(contract);
        details.contractMonth(contract.lastTradeDateOrContractMonth().substring(0, 6));
        details.underConid(VIX);
        return details;
    }
}
