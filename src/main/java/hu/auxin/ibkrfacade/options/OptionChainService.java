package hu.auxin.ibkrfacade.options;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsErrorCodes;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Options expiring together with a VX future, with live quotes. The contracts are resolved with reqContractDetails
 * and cached by expiration. Every option quote uses a market data line of the account, so the subscriptions made
 * here are limited: when a chain needs more lines, the subscriptions of the least recently requested chain are
 * cancelled first.
 */
@Slf4j
@Service
public class OptionChainService {

    private static final Duration CONTRACT_DETAILS_TIMEOUT = Duration.ofSeconds(60);
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /**
     * The options may stop trading a few days before the future (VIX index options: the day before)
     */
    private static final int MAX_EXPIRATION_DISTANCE_DAYS = 4;

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final ContractManagerService contractManagerService;
    private final MarketDataService marketDataService;
    private final int maxSubscriptions;
    private final Duration cacheTtl;
    private final Duration quoteWait;
    private final Clock clock;

    private final Map<String, CachedChain> cache = new ConcurrentHashMap<>();
    /**
     * Option conids of the chains in access order (the eldest first), guarded by this
     */
    private final LinkedHashMap<String, Set<Integer>> chainSubscriptions = new LinkedHashMap<>(16, 0.75f, true);
    /**
     * The option subscriptions made by this service, only these are cancelled here. Guarded by this.
     */
    private final Set<Integer> owned = new HashSet<>();

    @Autowired
    public OptionChainService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                              ContractManagerService contractManagerService, MarketDataService marketDataService,
                              @Value("${ibkr.option.max-subscriptions:60}") int maxSubscriptions,
                              @Value("${ibkr.option.chain-cache-ttl:12h}") Duration cacheTtl,
                              @Value("${ibkr.price.quote-wait:3s}") Duration quoteWait) {
        this(connection, requestRegistry, contractManagerService, marketDataService, maxSubscriptions, cacheTtl,
                quoteWait, Clock.systemUTC());
    }

    OptionChainService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                       ContractManagerService contractManagerService, MarketDataService marketDataService,
                       int maxSubscriptions, Duration cacheTtl, Duration quoteWait, Clock clock) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.contractManagerService = contractManagerService;
        this.marketDataService = marketDataService;
        this.maxSubscriptions = Math.max(1, maxSubscriptions);
        this.cacheTtl = cacheTtl;
        this.quoteWait = quoteWait;
        this.clock = clock;
    }

    /**
     * @param strikeRange only the strikes between F * (1 - strikeRange) and F * (1 + strikeRange)
     * @param maxStrikes  the strikes closest to the price of the future, each with the requested rights
     * @throws ApiException 404 CONTRACT_NOT_FOUND for an unknown conid, 400 INVALID_FUTURE if it's not a VIX future,
     *                      404 OPTIONS_NOT_FOUND / FOP_NOT_AVAILABLE if there are no such options, 422
     *                      UNDERLYING_PRICE_UNAVAILABLE without a price of the future, 400 OPTION_SUBSCRIPTION_LIMIT
     *                      if the chain needs more market data lines than ibkr.option.max-subscriptions
     */
    public OptionChain getChain(int futureConid, OptionProduct product, double strikeRange, int maxStrikes,
                                Set<Types.Right> rights) {
        Contract future = future(futureConid);
        Chain chain = contracts(product, future);
        MarketPrice futurePrice = contractManagerService.getLastPriceByConid(futureConid);
        Double underlying = underlyingPrice(futurePrice);
        if (underlying == null) {
            throw ApiException.unprocessable("UNDERLYING_PRICE_UNAVAILABLE", "No price of the future " + futureConid
                    + " to select the strikes"
                    + (futurePrice.streamError() == null ? "" : ", market data error " + futurePrice.streamError()));
        }
        List<Double> strikes = selectStrikes(chain.strikes(), underlying, strikeRange, maxStrikes);
        Set<Double> selected = new HashSet<>(strikes);
        List<Contract> options = chain.contracts().stream()
                .filter(option -> selected.contains(option.strike()) && rights.contains(option.right()))
                .toList();
        if (options.size() > maxSubscriptions) {
            throw ApiException.badRequest("OPTION_SUBSCRIPTION_LIMIT", options.size() + " options requested, at most "
                    + maxSubscriptions + " can be subscribed (IBKR_OPTION_MAX_SUBSCRIPTIONS), use a lower max_strikes");
        }

        Instant since = Instant.ofEpochMilli(System.currentTimeMillis());
        List<Integer> subscribed = new ArrayList<>();
        String notConnected = null;
        try {
            subscribed = subscribe(chain.key(), options);
        } catch (TwsNotConnectedException e) {
            log.warn("No market data subscription for the options of {}, TWS is not connected", futureConid);
            notConnected = "TWS is not connected";
        }
        long deadline = System.nanoTime() + quoteWait.toNanos();
        for (int conid : subscribed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            marketDataService.awaitQuoteSince(conid, since, Duration.ofNanos(remaining));
        }

        List<OptionChain.Quote> quotes = new ArrayList<>();
        for (Contract option : options) {
            MarketPrice price = marketDataService.latestPrice(option.conid());
            String streamError = price.streamError() == null && !price.subscribed() ? notConnected : price.streamError();
            quotes.add(OptionChain.Quote.of(option.conid(), option.strike(), option.right().getApiString(), price,
                    marketDataService.modelGreeks(option.conid()), streamError));
        }
        return new OptionChain(futureConid, chain.expiration(), chain.multiplier(), chain.tradingClass(),
                underlying, quotes);
    }

    private Contract future(int futureConid) {
        ContractHolder holder = contractManagerService.getContractHolder(futureConid);
        Contract future = holder.getContract();
        if (future.secType() != Types.SecType.FUT) {
            throw ApiException.badRequest("INVALID_FUTURE", "Conid " + futureConid + " is not a future but "
                    + (future.secType() == null ? "unknown" : future.secType().getApiString()));
        }
        if (!"VIX".equals(future.symbol())) {
            throw ApiException.badRequest("INVALID_FUTURE", "Conid " + futureConid + " is a future of "
                    + future.symbol() + ", only VIX futures (VX) are supported");
        }
        return future;
    }

    /**
     * mid, or last, or the previous close
     */
    static Double underlyingPrice(MarketPrice price) {
        if (price.mid() != null) {
            return price.mid();
        }
        return price.last() != null ? price.last() : price.close();
    }

    /**
     * The maxStrikes strikes closest to the underlying price within underlying * (1 +- range), in ascending order.
     * On a tie the lower strike is preferred.
     */
    static List<Double> selectStrikes(Collection<Double> strikes, double underlying, double range, int maxStrikes) {
        double low = underlying * (1 - range);
        double high = underlying * (1 + range);
        return strikes.stream()
                .distinct()
                .filter(strike -> strike >= low - 1e-9 && strike <= high + 1e-9)
                .sorted(Comparator.comparingDouble((Double strike) -> Math.abs(strike - underlying))
                        .thenComparingDouble(strike -> strike))
                .limit(maxStrikes)
                .sorted()
                .toList();
    }

    // -- contracts

    Chain contracts(OptionProduct product, Contract future) {
        LocalDate expiration = expiration(future);
        String key = product + ":" + DATE.format(expiration) + (product.onFuture() ? ":" + future.conid() : "");
        Instant now = clock.instant();
        CachedChain cached = cache.get(key);
        if (cached != null && now.isBefore(cached.loadedAt().plus(cacheTtl))) {
            return cached.chain();
        }
        Chain chain = load(key, product, future, expiration);
        cache.put(key, new CachedChain(chain, now));
        cache.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().loadedAt().plus(cacheTtl)));
        return chain;
    }

    private Chain load(String key, OptionProduct product, Contract future, LocalDate futureExpiration) {
        Contract query = new Contract();
        query.symbol(future.symbol());
        query.secType(product.secType());
        query.exchange(product.exchange());
        query.currency(future.currency());
        if (product.tradingClass() != null) {
            query.tradingClass(product.tradingClass());
        }
        // the month, as the options may expire a few days earlier than the future
        query.lastTradeDateOrContractMonth(DATE.format(futureExpiration).substring(0, 6));
        List<ContractDetails> details;
        try {
            details = requestRegistry.call(id -> connection.client().reqContractDetails(id, query),
                    CONTRACT_DETAILS_TIMEOUT);
        } catch (TwsException e) {
            if (e.getCode() != TwsErrorCodes.NO_SECURITY_DEFINITION) {
                throw e;
            }
            details = List.of();
        }
        TreeMap<LocalDate, List<Contract>> byExpiration = new TreeMap<>();
        for (ContractDetails detail : details) {
            Contract option = detail.contract();
            if (product.tradingClass() != null && !product.tradingClass().equals(option.tradingClass())) {
                continue;
            }
            if (product.onFuture() && detail.underConid() > 0 && detail.underConid() != future.conid()) {
                continue;
            }
            LocalDate expiration = parseDate(option.lastTradeDateOrContractMonth());
            if (expiration != null && option.right() != null && option.right() != Types.Right.None) {
                byExpiration.computeIfAbsent(expiration, date -> new ArrayList<>()).add(option);
            }
        }
        LocalDate expiration = closest(byExpiration.keySet(), futureExpiration);
        if (expiration == null) {
            String detail = "No " + product + " options expiring with the future " + future.conid() + " ("
                    + future.localSymbol() + ", " + DATE.format(futureExpiration) + ")";
            if (product.onFuture()) {
                throw ApiException.notFound("FOP_NOT_AVAILABLE", detail
                        + ": IBKR has no options on this VX future (secType FOP on CFE), use product=VIX");
            }
            throw ApiException.notFound("OPTIONS_NOT_FOUND", detail);
        }
        List<Contract> options = byExpiration.get(expiration).stream()
                .sorted(Comparator.comparingDouble((Contract option) -> option.strike())
                        .thenComparing(option -> option.right().getApiString()))
                .toList();
        Contract first = options.get(0);
        log.info("Resolved {} {} options expiring on {} for the future {}", options.size(), product,
                DATE.format(expiration), future.conid());
        return new Chain(key, DATE.format(expiration), multiplier(first.multiplier()), first.tradingClass(), options);
    }

    /**
     * The expiration closest to the one of the future, at most MAX_EXPIRATION_DISTANCE_DAYS away, on a tie the
     * earlier one.
     */
    static LocalDate closest(Collection<LocalDate> expirations, LocalDate target) {
        return expirations.stream()
                .filter(date -> Math.abs(ChronoUnit.DAYS.between(target, date)) <= MAX_EXPIRATION_DISTANCE_DAYS)
                .min(Comparator.comparingLong((LocalDate date) -> Math.abs(ChronoUnit.DAYS.between(target, date)))
                        .thenComparing(date -> date))
                .orElse(null);
    }

    private static LocalDate expiration(Contract future) {
        LocalDate date = parseDate(future.lastTradeDateOrContractMonth());
        if (date == null) {
            throw ApiException.badRequest("INVALID_FUTURE", "The future " + future.conid()
                    + " has no last trade date: " + future.lastTradeDateOrContractMonth());
        }
        return date;
    }

    /**
     * YYYYMMDD, optionally followed by a time ("20261118 08:00 US/Central"); null for a contract month or garbage
     */
    private static LocalDate parseDate(String value) {
        if (value == null || value.length() < 8) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 8), DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Integer multiplier(String multiplier) {
        if (!StringUtils.hasText(multiplier)) {
            return null;
        }
        try {
            return new BigDecimal(multiplier.trim()).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }
    }

    // -- market data lines

    /**
     * Subscribes to the options of the chain, and cancels the option subscriptions which are no longer needed:
     * the ones of the previous request of the same chain, and those of the least recently requested chains while
     * there are more than maxSubscriptions.
     *
     * @return the conids subscribed now
     */
    synchronized List<Integer> subscribe(String key, List<Contract> options) {
        Set<Integer> wanted = new HashSet<>();
        options.forEach(option -> wanted.add(option.conid()));
        Set<Integer> previous = chainSubscriptions.remove(key);
        chainSubscriptions.put(key, wanted);
        if (previous != null) {
            release(previous);
        }
        Iterator<Map.Entry<String, Set<Integer>>> eldest = chainSubscriptions.entrySet().iterator();
        while (lines() > maxSubscriptions && eldest.hasNext()) {
            Map.Entry<String, Set<Integer>> entry = eldest.next();
            if (entry.getKey().equals(key)) {
                break;
            }
            eldest.remove();
            log.info("Cancelling the option subscriptions of {} ({} options), more than {} option lines",
                    entry.getKey(), entry.getValue().size(), maxSubscriptions);
            release(entry.getValue());
        }
        List<Integer> subscribed = new ArrayList<>();
        for (Contract option : options) {
            if (!marketDataService.isSubscribed(option.conid())) {
                marketDataService.subscribe(option);
                owned.add(option.conid());
                subscribed.add(option.conid());
            }
        }
        return subscribed;
    }

    /**
     * Cancels the owned subscriptions which are not used by any chain
     */
    private void release(Set<Integer> conids) {
        for (int conid : conids) {
            if (owned.contains(conid) && chainSubscriptions.values().stream().noneMatch(set -> set.contains(conid))) {
                owned.remove(conid);
                marketDataService.unsubscribe(conid);
            }
        }
    }

    private int lines() {
        Set<Integer> all = new HashSet<>();
        chainSubscriptions.values().forEach(all::addAll);
        return all.size();
    }

    /**
     * Number of option subscriptions held by this service
     */
    synchronized int ownedSubscriptions() {
        return (int) owned.stream().filter(marketDataService::isSubscribed).count();
    }

    record Chain(String key, String expiration, Integer multiplier, String tradingClass, List<Contract> contracts) {

        List<Double> strikes() {
            return contracts.stream().map(Contract::strike).distinct().toList();
        }
    }

    private record CachedChain(Chain chain, Instant loadedAt) {
    }
}
