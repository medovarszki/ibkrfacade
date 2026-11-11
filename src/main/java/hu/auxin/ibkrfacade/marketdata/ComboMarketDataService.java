package hu.auxin.ibkrfacade.marketdata;

import com.ib.client.ComboLeg;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.TickType;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsDataLostEvent;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market data of exchange listed calendar spreads (BAG contracts: buy the far, sell the near leg). A combo has no
 * conid, so its ticks are kept in memory only. Every subscription uses a market data line of the account, so only
 * the most recently requested spreads stay subscribed.
 */
@Slf4j
@Service
public class ComboMarketDataService {

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final int maxSubscriptions;
    private final Duration staleAfter;
    private final Duration quoteWait;

    /**
     * Subscriptions by "nearConid:farConid" in access order, the eldest is cancelled when there are too many
     */
    private final LinkedHashMap<String, Subscription> subscriptions = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<Integer, Subscription> byTicker = new ConcurrentHashMap<>();

    public ComboMarketDataService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                                  @Value("${ibkr.spread.max-subscriptions:10}") int maxSubscriptions,
                                  @Value("${ibkr.price.stale-after:${ibkr.order.max-quote-age:15m}}") Duration staleAfter,
                                  @Value("${ibkr.price.quote-wait:3s}") Duration quoteWait) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.maxSubscriptions = Math.max(1, maxSubscriptions);
        this.staleAfter = staleAfter;
        this.quoteWait = quoteWait;
    }

    /**
     * Latest quote of the spread. Without a subscription it subscribes, and waits at most ibkr.price.quote-wait
     * for the first quote.
     */
    public SpreadQuote.Native quote(Contract near, Contract far) {
        boolean[] created = new boolean[1];
        Subscription subscription = subscribe(near, far, created);
        if (created[0]) {
            long deadline = System.nanoTime() + quoteWait.toNanos();
            while (!subscription.hasQuote() && subscription.streamError == null && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return subscription.toQuote(byTicker.containsKey(subscription.tickerId), Instant.now(), staleAfter);
    }

    private synchronized Subscription subscribe(Contract near, Contract far, boolean[] created) {
        String key = near.conid() + ":" + far.conid();
        Subscription existing = subscriptions.get(key);
        if (existing != null) {
            return existing;
        }
        EClientSocket client = connection.client();
        Subscription subscription = new Subscription(requestRegistry.nextId(), key, bag(near, far));
        Iterator<Subscription> eldest = subscriptions.values().iterator();
        while (subscriptions.size() >= maxSubscriptions && eldest.hasNext()) {
            Subscription evicted = eldest.next();
            eldest.remove();
            byTicker.remove(evicted.tickerId);
            client.cancelMktData(evicted.tickerId);
            log.info("Cancelled the market data of spread {} (tickerId={})", evicted.key, evicted.tickerId);
        }
        subscriptions.put(key, subscription);
        byTicker.put(subscription.tickerId, subscription);
        client.reqMktData(subscription.tickerId, subscription.contract, "", false, false, null);
        created[0] = true;
        log.info("Subscribed to market data of spread {} (tickerId={})", key, subscription.tickerId);
        return subscription;
    }

    /**
     * Calendar spread as a combo: BUY 1 far, SELL 1 near, quoted as far - near.
     */
    static Contract bag(Contract near, Contract far) {
        Contract bag = new Contract();
        bag.symbol(near.symbol());
        bag.secType(Types.SecType.BAG);
        bag.exchange(near.exchange());
        bag.currency(near.currency());
        List<ComboLeg> legs = new ArrayList<>();
        legs.add(new ComboLeg(far.conid(), 1, Types.Action.BUY.getApiString(), far.exchange(), 0));
        legs.add(new ComboLeg(near.conid(), 1, Types.Action.SELL.getApiString(), near.exchange(), 0));
        bag.comboLegs(legs);
        return bag;
    }

    // -- callbacks

    /**
     * @return false if the ticker id does not belong to a spread subscription
     */
    public boolean onTick(int tickerId, int field, double price) {
        Subscription subscription = byTicker.get(tickerId);
        if (subscription == null) {
            return false;
        }
        if (subscription.quote.price(TickType.get(field), price, Instant.now())) {
            subscription.streamError = null;
        }
        return true;
    }

    public boolean onTickSize(int tickerId, int field, Decimal size) {
        Subscription subscription = byTicker.get(tickerId);
        if (subscription == null) {
            return false;
        }
        subscription.quote.size(TickType.get(field), size);
        return true;
    }

    public boolean onStreamError(int tickerId, int code, String message) {
        Subscription subscription = byTicker.get(tickerId);
        if (subscription == null) {
            return false;
        }
        subscription.streamError = code + ": " + message;
        return true;
    }

    public boolean onMarketDataType(int tickerId, int type) {
        Subscription subscription = byTicker.get(tickerId);
        if (subscription == null) {
            return false;
        }
        subscription.marketDataType = MarketDataType.of(type);
        return true;
    }

    @EventListener
    public synchronized void onConnected(TwsConnectedEvent event) {
        EClientSocket client = connection.client();
        subscriptions.values().forEach(subscription ->
                client.reqMktData(subscription.tickerId, subscription.contract, "", false, false, null));
    }

    @EventListener
    public synchronized void onDataLost(TwsDataLostEvent event) {
        EClientSocket client = connection.client();
        subscriptions.values().forEach(subscription -> {
            client.cancelMktData(subscription.tickerId);
            client.reqMktData(subscription.tickerId, subscription.contract, "", false, false, null);
        });
    }

    private static final class Subscription {

        private final int tickerId;
        private final String key;
        private final Contract contract;
        private final LiveQuote quote = new LiveQuote();
        private volatile String streamError;
        private volatile MarketDataType marketDataType;

        private Subscription(int tickerId, String key, Contract contract) {
            this.tickerId = tickerId;
            this.key = key;
            this.contract = contract;
        }

        private boolean hasQuote() {
            return quote.comboBid() != null || quote.comboAsk() != null;
        }

        private SpreadQuote.Native toQuote(boolean subscribed, Instant now, Duration staleAfter) {
            Double bid = quote.comboBid();
            Double ask = quote.comboAsk();
            Instant bidTime = bid == null ? null : quote.bidTime();
            Instant askTime = ask == null ? null : quote.askTime();
            Instant quoteTime = bidTime == null ? askTime
                    : askTime == null || bidTime.isAfter(askTime) ? bidTime : askTime;
            Long age = quoteTime == null ? null : Math.max(0, Duration.between(quoteTime, now).toSeconds());
            boolean stale = quoteTime == null
                    || (staleAfter.isPositive() && quoteTime.isBefore(now.minus(staleAfter)));
            MarketStats stats = quote.stats();
            return new SpreadQuote.Native(bid, ask,
                    bid == null || ask == null ? null : MarketPrice.round((bid + ask) / 2), quote.last(),
                    stats.close(), stats.bidSize(), stats.askSize(), stats.lastSize(), stats.volume(), bidTime,
                    askTime, quote.lastTime(), age, stale, subscribed, streamError, marketDataType);
        }
    }
}
