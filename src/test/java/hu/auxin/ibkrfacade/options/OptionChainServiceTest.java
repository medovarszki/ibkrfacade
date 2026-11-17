package hu.auxin.ibkrfacade.options;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.TickType;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.marketdata.OptionGreeks;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsErrorCodes;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.timeseries.TSElement;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Options of a VX future with live quotes (TWS client mocked).
 */
class OptionChainServiceTest {

    private static final int NOV = 1001;
    private static final int DEC = 1002;
    private static final Set<Types.Right> CALLS_AND_PUTS = EnumSet.of(Types.Right.Call, Types.Right.Put);

    private final TwsConnection connection = mock(TwsConnection.class);
    private final EClientSocket client = mock(EClientSocket.class);
    private final TimeSeriesHandler timeSeries = mock(TimeSeriesHandler.class);
    private final ContractRepository contractRepository = mock(ContractRepository.class);
    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));
    private final MarketDataService marketData = new MarketDataService(connection, registry, timeSeries, 1, false,
            Duration.ofMinutes(15));
    private final ContractManagerService contracts = new ContractManagerService(connection, registry,
            contractRepository, marketData, Duration.ofMillis(300));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-17T15:00:00Z"));
    private final OptionChainService service = service(4);

    private final Map<String, TSElement> redis = new ConcurrentHashMap<>();
    private final List<Contract> optionQueries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(connection.client()).thenReturn(client);
        when(contractRepository.findById(NOV)).thenReturn(Optional.of(new ContractHolder(future(NOV, "VXX6", "20261118"))));
        when(contractRepository.findById(DEC)).thenReturn(Optional.of(new ContractHolder(future(DEC, "VXZ6", "20261216"))));
        when(contractRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(timeSeries.latest(anyInt(), any())).thenAnswer(invocation ->
                redis.get(TimeSeriesHandler.key(invocation.getArgument(0), invocation.getArgument(1))));
        when(timeSeries.addToStream(anyInt(), anyDouble(), any())).thenAnswer(invocation -> {
            long now = System.currentTimeMillis();
            redis.put(TimeSeriesHandler.key(invocation.getArgument(0), invocation.getArgument(2)),
                    new TSElement(now, invocation.getArgument(1)));
            return now;
        });
        // the futures quote at once, the options answer with a quote and the model values
        doAnswer(invocation -> {
            int tickerId = invocation.getArgument(0);
            Contract contract = invocation.getArgument(1);
            if (contract.secType() == Types.SecType.FUT) {
                quote(tickerId, contract.conid() == NOV ? 20.30 : 21.30, contract.conid() == NOV ? 20.40 : 21.40);
            } else {
                quote(tickerId, 1.35, 1.45);
                marketData.onTickSize(tickerId, TickType.BID_SIZE.index(), Decimal.get(520));
                marketData.onTickSize(tickerId, TickType.ASK_SIZE.index(), Decimal.get(80));
                marketData.onOptionComputation(tickerId, TickType.MODEL_OPTION.index(), 0.82, 0.55, 0.09, 0.021,
                        -0.04);
            }
            return null;
        }).when(client).reqMktData(anyInt(), any(), any(), eq(false), eq(false), any());
        doAnswer(invocation -> {
            int id = invocation.getArgument(0);
            Contract query = invocation.getArgument(1);
            optionQueries.add(query);
            if (query.secType() == Types.SecType.FOP) {
                registry.fail(id, new TwsException(TwsErrorCodes.NO_SECURITY_DEFINITION, "No security definition"));
                return null;
            }
            String month = query.lastTradeDateOrContractMonth();
            String expiration = month.equals("202611") ? "20261117" : "20261215";
            int base = month.equals("202611") ? 10_000 : 20_000;
            for (int strike = 14; strike <= 30; strike++) {
                registry.addItem(id, option(base + strike * 10, expiration, strike, Types.Right.Call, "VIX"));
                registry.addItem(id, option(base + strike * 10 + 1, expiration, strike, Types.Right.Put, "VIX"));
            }
            // a weekly of the same month, filtered by the trading class
            registry.addItem(id, option(base + 999, month + "08", 21, Types.Right.Call, "VIXW"));
            registry.complete(id);
            return null;
        }).when(client).reqContractDetails(anyInt(), argThat(query -> query.secType() != Types.SecType.FUT));
    }

    @Test
    void strikesClosestToTheFutureWithinTheRange() {
        List<Double> strikes = new ArrayList<>();
        for (int strike = 10; strike <= 40; strike++) {
            strikes.add((double) strike);
        }

        assertThat(OptionChainService.selectStrikes(strikes, 21.35, 0.25, 15))
                .containsExactly(17.0, 18.0, 19.0, 20.0, 21.0, 22.0, 23.0, 24.0, 25.0, 26.0);
        assertThat(OptionChainService.selectStrikes(strikes, 21.35, 0.25, 5))
                .containsExactly(19.0, 20.0, 21.0, 22.0, 23.0);
        assertThat(OptionChainService.selectStrikes(strikes, 21.35, 0.1, 15))
                .containsExactly(20.0, 21.0, 22.0, 23.0);
        // on a tie the lower strike wins
        assertThat(OptionChainService.selectStrikes(strikes, 20.5, 0.25, 1)).containsExactly(20.0);
        // range bounds are inclusive
        assertThat(OptionChainService.selectStrikes(List.of(15.0, 20.0, 25.0), 20.0, 0.25, 15))
                .containsExactly(15.0, 20.0, 25.0);
    }

    @Test
    void optionExpiringWithTheFutureIsTheClosestOneBeforeIt() {
        LocalDate future = LocalDate.of(2026, 12, 16);
        assertThat(OptionChainService.closest(List.of(LocalDate.of(2026, 12, 8), LocalDate.of(2026, 12, 15),
                LocalDate.of(2026, 12, 22)), future)).isEqualTo(LocalDate.of(2026, 12, 15));
        assertThat(OptionChainService.closest(List.of(LocalDate.of(2026, 12, 8)), future)).isNull();
    }

    @Test
    void chainWithConidsQuotesAndModelGreeks() {
        OptionChain chain = service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);

        assertThat(chain.futureConid()).isEqualTo(DEC);
        assertThat(chain.expiration()).isEqualTo("20261215");
        assertThat(chain.multiplier()).isEqualTo(100);
        assertThat(chain.tradingClass()).isEqualTo("VIX");
        assertThat(chain.underlyingPrice()).isEqualTo(21.35);
        assertThat(chain.options()).extracting(OptionChain.Quote::conid, OptionChain.Quote::strike, OptionChain.Quote::right)
                .containsExactly(tuple(20_210, 21.0, "C"), tuple(20_211, 21.0, "P"),
                        tuple(20_220, 22.0, "C"), tuple(20_221, 22.0, "P"));
        OptionChain.Quote call = chain.options().get(0);
        assertThat(call.bid()).isEqualTo(1.35);
        assertThat(call.ask()).isEqualTo(1.45);
        assertThat(call.bidSize()).hasToString("520");
        assertThat(call.askSize()).hasToString("80");
        assertThat(call.stale()).isFalse();
        assertThat(call.quoteAgeSeconds()).isBetween(0L, 1L);
        assertThat(call.modelIv()).isEqualTo(0.82);
        assertThat(call.modelDelta()).isEqualTo(0.55);
        assertThat(call.modelGamma()).isEqualTo(0.09);
        assertThat(call.modelVega()).isEqualTo(0.021);
        assertThat(call.modelTheta()).isEqualTo(-0.04);
        assertThat(call.streamError()).isNull();

        Contract query = optionQueries.get(0);
        assertThat(query.symbol()).isEqualTo("VIX");
        assertThat(query.secType()).isEqualTo(Types.SecType.OPT);
        assertThat(query.exchange()).isEqualTo("SMART");
        assertThat(query.tradingClass()).isEqualTo("VIX");
        assertThat(query.lastTradeDateOrContractMonth()).isEqualTo("202612");
        // the model values are in the default tick set of the options
        verify(client).reqMktData(anyInt(), argThat(contract -> contract.conid() == 20_210), eq(""), eq(false),
                eq(false), any());
    }

    @Test
    void onlyTheRequestedRights() {
        OptionChain chain = service.getChain(DEC, OptionProduct.VIX, 0.25, 3, EnumSet.of(Types.Right.Put));

        assertThat(chain.options()).extracting(OptionChain.Quote::right).containsOnly("P").hasSize(3);
    }

    @Test
    void contractsAreCachedByExpiration() {
        service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);
        service.getChain(DEC, OptionProduct.VIX, 0.25, 1, CALLS_AND_PUTS);
        assertThat(optionQueries).hasSize(1);

        clock.advance(Duration.ofHours(13));
        service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);
        assertThat(optionQueries).hasSize(2);
    }

    @Test
    void leastRecentlyRequestedChainIsUnsubscribedFirst() {
        OptionChain dec = service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);
        assertThat(dec.options()).allMatch(option -> marketData.isSubscribed(option.conid()));

        OptionChain nov = service.getChain(NOV, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);

        assertThat(nov.expiration()).isEqualTo("20261117");
        assertThat(dec.options()).noneMatch(option -> marketData.isSubscribed(option.conid()));
        assertThat(nov.options()).allMatch(option -> marketData.isSubscribed(option.conid()));
        verify(client, times(4)).cancelMktData(anyInt());
        assertThat(service.ownedSubscriptions()).isEqualTo(4);

        service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);
        assertThat(nov.options()).noneMatch(option -> marketData.isSubscribed(option.conid()));
        verify(client, times(8)).cancelMktData(anyInt());
        // the futures stay subscribed
        assertThat(marketData.isSubscribed(NOV)).isTrue();
        assertThat(marketData.isSubscribed(DEC)).isTrue();
    }

    @Test
    void chainsFitTogetherUnderTheLimit() {
        OptionChainService roomy = service(8);
        roomy.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);
        roomy.getChain(NOV, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);

        verify(client, never()).cancelMktData(anyInt());
        assertThat(roomy.ownedSubscriptions()).isEqualTo(8);
    }

    @Test
    void strikesLeavingTheChainAreUnsubscribed() {
        service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS); // 21, 22
        service.getChain(DEC, OptionProduct.VIX, 0.25, 1, CALLS_AND_PUTS); // 21

        assertThat(marketData.isSubscribed(20_210)).isTrue();
        assertThat(marketData.isSubscribed(20_220)).isFalse();
        assertThat(service.ownedSubscriptions()).isEqualTo(2);
    }

    @Test
    void optionsSubscribedElsewhereAreNotCancelled() {
        Contract option = option(20_210, "20261215", 21, Types.Right.Call, "VIX").contract();
        marketData.subscribe(option);

        service.getChain(DEC, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);
        service.getChain(NOV, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS);

        assertThat(marketData.isSubscribed(20_210)).isTrue();
        assertThat(marketData.isSubscribed(20_220)).isFalse();
    }

    @Test
    void chainLargerThanTheLimitIsRejected() {
        assertThatThrownBy(() -> service.getChain(DEC, OptionProduct.VIX, 0.25, 3, CALLS_AND_PUTS))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getCode()).isEqualTo("OPTION_SUBSCRIPTION_LIMIT"));
        verify(client, never()).reqMktData(anyInt(), argThat(contract -> contract.secType() == Types.SecType.OPT),
                any(), anyBoolean(), anyBoolean(), any());
    }

    @Test
    void missingMarketDataPermissionIsReportedOnTheOption() {
        doAnswer(invocation -> {
            int tickerId = invocation.getArgument(0);
            marketData.onStreamError(tickerId, TwsErrorCodes.MARKET_DATA_NOT_SUBSCRIBED,
                    "Requested market data is not subscribed");
            return null;
        }).when(client).reqMktData(anyInt(), argThat(contract -> contract != null
                && contract.secType() == Types.SecType.OPT), any(), eq(false), eq(false), any());

        OptionChain chain = service.getChain(DEC, OptionProduct.VIX, 0.25, 1, CALLS_AND_PUTS);

        assertThat(chain.options()).hasSize(2).allSatisfy(option -> {
            assertThat(option.streamError()).startsWith("354:");
            assertThat(option.stale()).isTrue();
            assertThat(option.bid()).isNull();
            assertThat(option.modelIv()).isNull();
        });
    }

    @Test
    void unknownConidIsNotFound() {
        when(contractRepository.findById(999)).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            registry.fail(invocation.getArgument(0), new TwsException(TwsErrorCodes.NO_SECURITY_DEFINITION, "No security definition"));
            return null;
        }).when(client).reqContractDetails(anyInt(), argThat(query -> query.conid() == 999));

        assertThatThrownBy(() -> service.getChain(999, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("CONTRACT_NOT_FOUND");
                    assertThat(e.getStatus().value()).isEqualTo(404);
                });
    }

    @Test
    void conidMustBeAVixFuture() {
        Contract stock = new Contract();
        stock.conid(265598);
        stock.symbol("AAPL");
        stock.secType(Types.SecType.STK);
        when(contractRepository.findById(265598)).thenReturn(Optional.of(new ContractHolder(stock)));
        Contract es = future(495512563, "ESZ6", "20261218");
        es.symbol("ES");
        when(contractRepository.findById(495512563)).thenReturn(Optional.of(new ContractHolder(es)));

        assertThatThrownBy(() -> service.getChain(265598, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("INVALID_FUTURE");
                    assertThat(e.getStatus().value()).isEqualTo(400);
                });
        assertThatThrownBy(() -> service.getChain(495512563, OptionProduct.VIX, 0.25, 2, CALLS_AND_PUTS))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("INVALID_FUTURE"));
    }

    @Test
    void optionsOnTheFutureWithoutSuchContracts() {
        assertThatThrownBy(() -> service.getChain(DEC, OptionProduct.VX_FOP, 0.25, 2, CALLS_AND_PUTS))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("FOP_NOT_AVAILABLE");
                    assertThat(e.getStatus().value()).isEqualTo(404);
                });
        assertThat(optionQueries.get(0).secType()).isEqualTo(Types.SecType.FOP);
        assertThat(optionQueries.get(0).exchange()).isEqualTo("CFE");
    }

    @Test
    void notComputedModelValuesAreNull() {
        OptionGreeks greeks = OptionGreeks.of(Double.MAX_VALUE, Double.MAX_VALUE, 0.09, Double.MAX_VALUE, -0.04);

        assertThat(greeks.impliedVol()).isNull();
        assertThat(greeks.delta()).isNull();
        assertThat(greeks.gamma()).isEqualTo(0.09);
        assertThat(greeks.vega()).isNull();
        assertThat(greeks.theta()).isEqualTo(-0.04);
        assertThat(OptionGreeks.of(-1, -2, 0, 0, 0).impliedVol()).isNull();
        assertThat(OptionGreeks.of(-1, -2, 0, 0, 0).delta()).isNull();
    }

    private OptionChainService service(int maxSubscriptions) {
        return new OptionChainService(connection, registry, contracts, marketData, maxSubscriptions,
                Duration.ofHours(12), Duration.ofMillis(300), clock);
    }

    private void quote(int tickerId, double bid, double ask) {
        marketData.onTick(tickerId, TickType.BID.index(), bid);
        marketData.onTick(tickerId, TickType.ASK.index(), ask);
    }

    private static Contract future(int conid, String localSymbol, String lastTradeDate) {
        Contract contract = new Contract();
        contract.conid(conid);
        contract.symbol("VIX");
        contract.secType(Types.SecType.FUT);
        contract.exchange("CFE");
        contract.currency("USD");
        contract.localSymbol(localSymbol);
        contract.tradingClass("VX");
        contract.multiplier("1000");
        contract.lastTradeDateOrContractMonth(lastTradeDate);
        return contract;
    }

    private static ContractDetails option(int conid, String expiration, double strike, Types.Right right,
                                          String tradingClass) {
        Contract contract = new Contract();
        contract.conid(conid);
        contract.symbol("VIX");
        contract.secType(Types.SecType.OPT);
        contract.exchange("SMART");
        contract.currency("USD");
        contract.tradingClass(tradingClass);
        contract.multiplier("100");
        contract.strike(strike);
        contract.right(right);
        contract.lastTradeDateOrContractMonth(expiration);
        ContractDetails details = new ContractDetails();
        details.contract(contract);
        details.underConid(13455763);
        return details;
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
