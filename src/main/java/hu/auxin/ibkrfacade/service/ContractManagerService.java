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

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final ContractRepository contractRepository;
    private final MarketDataService marketDataService;
    private final Duration priceQuoteWait;

    ContractManagerService(TwsConnection connection, TwsRequestRegistry requestRegistry,
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
            ContractDetails details = getContractDetails(contract);
            ContractHolder contractHolder = new ContractHolder(details.contract(), details);
            contractRepository.save(contractHolder);
            return contractHolder;
        });
    }

    private static ApiException contractNotFound(int conid) {
        return ApiException.notFound("CONTRACT_NOT_FOUND", "No contract found for conid " + conid);
    }
}
