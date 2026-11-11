package hu.auxin.ibkrfacade.marketdata;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.TickType;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsDataLostEvent;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import redis.clients.jedis.timeseries.TSElement;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Market data subscriptions. The ticks are written to RedisTimeSeries under stream:[conid]:[BID|ASK|LAST].
 * The sizes, volume, open interest and the previous close are kept in memory only.
 * Subscriptions are restored automatically after a reconnection.
 */
@Slf4j
@Service
public class MarketDataService {

    private static final String FUTURES_OPEN_INTEREST_TICK = "588";

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final TimeSeriesHandler timeSeriesHandler;

    /**
     * 1 = live, 2 = frozen, 3 = delayed, 4 = delayed frozen
     */
    private final int marketDataType;
    private final boolean tickByTick;
    /**
     * A quote older than this is reported as stale
     */
    private final Duration staleAfter;

    private final Map<Integer, Contract> contractsByTicker = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> tickerByConid = new ConcurrentHashMap<>();
    private final Map<Integer, String> streamErrors = new ConcurrentHashMap<>();
    private final Map<Integer, MarketDataType> marketDataTypes = new ConcurrentHashMap<>();
    /**
     * Sizes and daily statistics by conid, in memory only
     */
    private final Map<Integer, LiveQuote> quotes = new ConcurrentHashMap<>();

    public MarketDataService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                             TimeSeriesHandler timeSeriesHandler,
                             @Value("${ibkr.market-data-type:1}") int marketDataType,
                             @Value("${ibkr.tick-by-tick-stream:false}") boolean tickByTick,
                             @Value("${ibkr.price.stale-after:${ibkr.order.max-quote-age:15m}}") Duration staleAfter) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.timeSeriesHandler = timeSeriesHandler;
        this.marketDataType = marketDataType;
        this.tickByTick = tickByTick;
        this.staleAfter = staleAfter;
    }

    /**
     * Subscribes to the market data of the contract if it's not subscribed yet.
     *
     * @return the ticker id of the subscription
     */
    public synchronized int subscribe(Contract contract) {
        Integer existing = tickerByConid.get(contract.conid());
        if (existing != null) {
            return existing;
        }
        EClientSocket client = connection.client();
        int tickerId = requestRegistry.nextId();
        try {
            timeSeriesHandler.createStream(contract.conid(), contract);
        } catch (Exception e) {
            log.error("Could not create time series for conid {}: {}", contract.conid(), e.getMessage());
        }
        contractsByTicker.put(tickerId, contract);
        tickerByConid.put(contract.conid(), tickerId);
        streamErrors.remove(contract.conid());
        marketDataTypes.remove(contract.conid());
        request(client, tickerId, contract);
        log.info("Subscribed to market data of {} (conid={}, tickerId={})", contract.symbol(), contract.conid(), tickerId);
        return tickerId;
    }

    public boolean isSubscribed(int conid) {
        return tickerByConid.containsKey(conid);
    }

    public MarketPrice latestPrice(int conid) {
        TSElement bid = timeSeriesHandler.latest(conid, TickType.BID);
        TSElement ask = timeSeriesHandler.latest(conid, TickType.ASK);
        TSElement last = timeSeriesHandler.latest(conid, TickType.LAST);
        LiveQuote quote = quotes.get(conid);
        return MarketPrice.of(conid, value(bid), value(ask), value(last), time(bid), time(ask), time(last),
                isSubscribed(conid), streamErrors.get(conid), marketDataTypes.get(conid), Instant.now(), staleAfter,
                quoteBasis(conid), quote == null ? MarketStats.EMPTY : quote.stats());
    }

    /**
     * Indices have no bid/ask, their freshness is based on the latest value of the index.
     */
    private MarketPrice.QuoteBasis quoteBasis(int conid) {
        Integer tickerId = tickerByConid.get(conid);
        Contract contract = tickerId == null ? null : contractsByTicker.get(tickerId);
        return contract != null && contract.secType() == Types.SecType.IND
                ? MarketPrice.QuoteBasis.LAST : MarketPrice.QuoteBasis.BID_ASK;
    }

    /**
     * Subscribes if needed, and waits at most maxWait for a quote which is not older than maxAge.
     */
    public MarketPrice awaitQuote(Contract contract, Duration maxAge, Duration maxWait) {
        subscribe(contract);
        return await(contract.conid(), price -> price.hasQuote(maxAge, Instant.now()), maxWait);
    }

    /**
     * Waits at most maxWait for a bid or ask which arrived at or after the given time, eg. the first quote of a new
     * subscription. Returns the latest price (possibly an older quote) when no such quote arrives in time.
     */
    public MarketPrice awaitQuoteSince(int conid, Instant since, Duration maxWait) {
        return await(conid, price -> price.quoteTime() != null && !price.quoteTime().isBefore(since), maxWait);
    }

    private MarketPrice await(int conid, Predicate<MarketPrice> done, Duration maxWait) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        MarketPrice price = latestPrice(conid);
        while (!done.test(price) && price.streamError() == null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            price = latestPrice(conid);
        }
        return price;
    }

    // -- callbacks

    /**
     * @return false if the ticker id does not belong to a market data subscription
     */
    public boolean onTick(int tickerId, int field, double price) {
        Contract contract = contractsByTicker.get(tickerId);
        if (contract == null) {
            return false;
        }
        TickType tickType = TickType.get(field);
        TickType side = side(tickType);
        if (price > 0) {
            if (side != null) {
                timeSeriesHandler.addToStream(contract.conid(), price, side);
                streamErrors.remove(contract.conid());
            } else {
                quote(contract.conid()).price(tickType, price, Instant.now());
            }
        }
        return true;
    }

    /**
     * Sizes, volume and open interest.
     *
     * @return false if the ticker id does not belong to a market data subscription
     */
    public boolean onTickSize(int tickerId, int field, Decimal size) {
        Contract contract = contractsByTicker.get(tickerId);
        if (contract == null) {
            return false;
        }
        quote(contract.conid()).size(TickType.get(field), size);
        return true;
    }

    private LiveQuote quote(int conid) {
        return quotes.computeIfAbsent(conid, key -> new LiveQuote());
    }

    /**
     * @return false if the ticker id does not belong to a market data subscription
     */
    public boolean onStreamError(int tickerId, int code, String message) {
        Contract contract = contractsByTicker.get(tickerId);
        if (contract == null) {
            return false;
        }
        streamErrors.put(contract.conid(), code + ": " + message);
        return true;
    }

    /**
     * @return false if the ticker id does not belong to a market data subscription
     */
    public boolean onMarketDataType(int tickerId, int type) {
        Contract contract = contractsByTicker.get(tickerId);
        if (contract == null) {
            return false;
        }
        MarketDataType marketDataType = MarketDataType.of(type);
        if (marketDataType != null && marketDataTypes.put(contract.conid(), marketDataType) != marketDataType) {
            log.info("Market data type of {} (conid={}): {}", contract.symbol(), contract.conid(), marketDataType);
        }
        return true;
    }

    @EventListener
    public void onConnected(TwsConnectedEvent event) {
        EClientSocket client = connection.client();
        client.reqMarketDataType(marketDataType);
        contractsByTicker.forEach((tickerId, contract) -> request(client, tickerId, contract));
        if (!contractsByTicker.isEmpty()) {
            log.info("Restored {} market data subscription(s)", contractsByTicker.size());
        }
    }

    @EventListener
    public void onDataLost(TwsDataLostEvent event) {
        EClientSocket client = connection.client();
        contractsByTicker.forEach((tickerId, contract) -> {
            cancel(client, tickerId);
            request(client, tickerId, contract);
        });
    }

    private void request(EClientSocket client, int tickerId, Contract contract) {
        if (tickByTick) {
            client.reqTickByTickData(tickerId, contract, "BidAsk", 0, false);
        } else {
            client.reqMktData(tickerId, contract, genericTicks(contract), false, false, null);
        }
    }

    /**
     * The open interest of futures is not part of the default tick set.
     */
    static String genericTicks(Contract contract) {
        return contract.secType() == Types.SecType.FUT ? FUTURES_OPEN_INTEREST_TICK : "";
    }

    private void cancel(EClientSocket client, int tickerId) {
        if (tickByTick) {
            client.cancelTickByTickData(tickerId);
        } else {
            client.cancelMktData(tickerId);
        }
    }

    private static TickType side(TickType tickType) {
        return switch (tickType) {
            case BID, DELAYED_BID -> TickType.BID;
            case ASK, DELAYED_ASK -> TickType.ASK;
            case LAST, DELAYED_LAST -> TickType.LAST;
            default -> null;
        };
    }

    private static Double value(TSElement element) {
        return element == null ? null : element.getValue();
    }

    private static Instant time(TSElement element) {
        return element == null ? null : Instant.ofEpochMilli(element.getTimestamp());
    }
}
