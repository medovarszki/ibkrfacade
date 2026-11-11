package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.Option;
import hu.auxin.ibkrfacade.data.holder.OptionParameters;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsErrorCodes;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsNotConnectedException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Contract related operations. The service acts as a bridge between the TWS itself and the other parts of the system,
 * eg. the REST API or the strategy implementations.
 */
@Slf4j
@Service
public class ContractManagerService {

    private static final Duration OPTION_CHAIN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration FUTURES_CHAIN_TIMEOUT = Duration.ofSeconds(30);

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final ContractRepository contractRepository;
    private final MarketDataService marketDataService;
    private final Duration priceQuoteWait;

    public ContractManagerService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                           ContractRepository contractRepository, MarketDataService marketDataService,
                           @Value("${ibkr.price.quote-wait:3s}") Duration priceQuoteWait) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.contractRepository = contractRepository;
        this.marketDataService = marketDataService;
        this.priceQuoteWait = priceQuoteWait;
    }

    public List<Contract> searchContract(String search) {
        if (!StringUtils.hasText(search)) {
            throw ApiException.badRequest("INVALID_QUERY", "Search parameter cannot be empty");
        }
        return requestRegistry.call(id -> connection.client().reqMatchingSymbols(id, search));
    }

    public ContractDetails getContractDetails(Contract contract) {
        List<ContractDetails> details;
        try {
            details = requestRegistry.call(id -> connection.client().reqContractDetails(id, contract));
        } catch (TwsException e) {
            if (e.getCode() == TwsErrorCodes.NO_SECURITY_DEFINITION) {
                throw contractNotFound(contract.conid());
            }
            throw e;
        }
        if (details.isEmpty()) {
            throw contractNotFound(contract.conid());
        }
        if (details.size() > 1) {
            log.warn("Ambiguous contract definition, {} matches, using the first one", details.size());
        }
        return details.get(0);
    }

    public int subscribeMarketData(Contract contract) {
        return subscribeMarketData(contract.conid());
    }

    public int subscribeMarketData(int conid) {
        ContractHolder contractHolder = getContractHolder(conid);
        int tickerId = marketDataService.subscribe(contractHolder.getContract());
        if (contractHolder.getStreamRequestId() == null || contractHolder.getStreamRequestId() != tickerId) {
            contractHolder.setStreamRequestId(tickerId);
            contractRepository.save(contractHolder);
        }
        return tickerId;
    }

    /**
     * Returns the latest available bid/ask/last price for the given conid. Without an active market data
     * subscription it subscribes, and waits at most ibkr.price.quote-wait for the first quote. The price from Redis
     * may be old (eg. from before a restart of the facade), see {@link MarketPrice#stale()}.
     */
    public MarketPrice getLastPriceByConid(int conid) {
        return getLastPriceByConid(conid, null);
    }

    /**
     * @param maxAge if not null, a price without a bid or ask not older than this is rejected with 422
     *               STALE_MARKET_DATA
     */
    public MarketPrice getLastPriceByConid(int conid, Duration maxAge) {
        MarketPrice price = marketDataService.isSubscribed(conid)
                ? marketDataService.latestPrice(conid)
                : subscribeAndAwaitQuote(conid);
        if (maxAge != null && !price.hasQuote(maxAge, Instant.now())) {
            StringBuilder detail = new StringBuilder("No bid/ask for conid ").append(conid)
                    .append(" in the last ").append(maxAge.toSeconds()).append("s");
            if (price.quoteTime() != null) {
                detail.append(", the latest quote is from ").append(price.quoteTime());
            }
            if (price.streamError() != null) {
                detail.append(", market data error ").append(price.streamError());
            }
            throw ApiException.unprocessable("STALE_MARKET_DATA", detail.toString());
        }
        return price;
    }

    /**
     * Latest prices of several instruments in the order of the conids. The ones without a market data subscription
     * are subscribed, and the request waits at most ibkr.price.quote-wait altogether for their first quotes.
     *
     * @throws ApiException with 404 if any of the conids is unknown
     */
    public List<MarketPrice> getLastPrices(List<Integer> conids) {
        Instant since = Instant.ofEpochMilli(System.currentTimeMillis());
        List<Integer> subscribed = new ArrayList<>();
        try {
            for (int conid : conids) {
                if (!marketDataService.isSubscribed(conid)) {
                    subscribeMarketData(conid);
                    subscribed.add(conid);
                }
            }
        } catch (TwsNotConnectedException e) {
            log.warn("No market data subscription for {} conid(s), TWS is not connected", conids.size());
        }
        long deadline = System.nanoTime() + priceQuoteWait.toNanos();
        for (int conid : subscribed) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                break;
            }
            marketDataService.awaitQuoteSince(conid, since, Duration.ofNanos(remaining));
        }
        return conids.stream().map(marketDataService::latestPrice).toList();
    }

    /**
     * The futures of an underlying ordered by expiration. The contracts are saved, so they can be used by conid
     * afterwards (expired ones as well).
     *
     * @param tradingClass   optional, eg. VX for the monthly VIX futures (the weekly ones are VX01..VX53)
     * @param includeExpired with the expired contracts (TWS knows the ones expired in the last two years)
     * @throws ApiException with 404 if there is no such future
     */
    public List<ContractHolder> getFuturesChain(String symbol, String exchange, String currency, String tradingClass,
                                                boolean includeExpired) {
        Contract query = new Contract();
        query.symbol(symbol);
        query.secType(Types.SecType.FUT);
        query.exchange(exchange);
        if (StringUtils.hasText(currency)) {
            query.currency(currency);
        }
        if (StringUtils.hasText(tradingClass)) {
            query.tradingClass(tradingClass);
        }
        query.includeExpired(includeExpired);
        List<ContractDetails> details;
        try {
            details = requestRegistry.call(id -> connection.client().reqContractDetails(id, query),
                    FUTURES_CHAIN_TIMEOUT);
        } catch (TwsException e) {
            if (e.getCode() != TwsErrorCodes.NO_SECURITY_DEFINITION) {
                throw e;
            }
            details = List.of();
        }
        List<ContractHolder> chain = details.stream()
                .filter(detail -> !StringUtils.hasText(tradingClass)
                        || tradingClass.equals(detail.contract().tradingClass()))
                .sorted(Comparator.comparing((ContractDetails detail) ->
                                String.valueOf(detail.contract().lastTradeDateOrContractMonth()))
                        .thenComparingInt(detail -> detail.contract().conid()))
                .map(detail -> contractRepository.findById(detail.contract().conid()).orElseGet(() ->
                        contractRepository.save(new ContractHolder(detail.contract(), detail))))
                .toList();
        if (chain.isEmpty()) {
            throw ApiException.notFound("CONTRACT_NOT_FOUND", "No futures found for " + symbol + " on " + exchange
                    + (StringUtils.hasText(tradingClass) ? " with trading class " + tradingClass : ""));
        }
        return chain;
    }

    private MarketPrice subscribeAndAwaitQuote(int conid) {
        Instant since = Instant.ofEpochMilli(System.currentTimeMillis());
        try {
            subscribeMarketData(conid);
        } catch (TwsNotConnectedException e) {
            log.warn("No market data subscription for conid {}, TWS is not connected", conid);
            return marketDataService.latestPrice(conid);
        }
        return marketDataService.awaitQuoteSince(conid, since, priceQuoteWait);
    }

    /**
     * Getting the option chain for an underlying instrument
     */
    public Collection<Option> getOptionChainByConid(int underlyingConid) {
        ContractHolder underlying = getContractHolder(underlyingConid);
        Contract contract = underlying.getContract();
        List<OptionParameters> parameters = requestRegistry.call(id -> connection.client().reqSecDefOptParams(
                id, contract.symbol(), "", contract.secType().getApiString(), contract.conid()), OPTION_CHAIN_TIMEOUT);

        Set<Option> optionChain = new HashSet<>();
        for (OptionParameters parameter : parameters) {
            for (Types.Right right : List.of(Types.Right.Call, Types.Right.Put)) {
                for (String expiration : parameter.expirations()) {
                    for (Double strike : parameter.strikes()) {
                        String optionSymbol = contract.symbol() + " " + expiration + " " + right;
                        optionChain.add(new Option(optionSymbol, expiration, strike, right));
                    }
                }
            }
        }
        underlying.setOptionChain(optionChain);
        contractRepository.save(underlying);
        return optionChain.stream()
                .sorted(Comparator.comparing(Option::getExpiration)
                        .thenComparingDouble(Option::getStrike)
                        .thenComparing(Option::getRight))
                .toList();
    }

    /**
     * Returns the ContractHolder from Redis if the contract is already known. Otherwise, it's requested from TWS by
     * conid and saved to Redis.
     *
     * @throws ApiException with 404 if there's no such contract
     */
    public ContractHolder getContractHolder(int conid) {
        return contractRepository.findById(conid).orElseGet(() -> {
            Contract contract = new Contract();
            contract.conid(conid);
            ContractDetails details;
            try {
                details = getContractDetails(contract);
            } catch (ApiException e) {
                // an expired future is only found with includeExpired
                contract.secType(Types.SecType.FUT);
                contract.includeExpired(true);
                details = getContractDetails(contract);
            }
            ContractHolder contractHolder = new ContractHolder(details.contract(), details);
            contractRepository.save(contractHolder);
            return contractHolder;
        });
    }

    private static ApiException contractNotFound(int conid) {
        return ApiException.notFound("CONTRACT_NOT_FOUND", "No contract found for conid " + conid);
    }
}
