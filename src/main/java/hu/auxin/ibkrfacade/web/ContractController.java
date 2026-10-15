package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.data.holder.Option;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.session.TradingSession;
import hu.auxin.ibkrfacade.session.TradingSessionService;
import hu.auxin.ibkrfacade.web.dto.ContractDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Collection;
import java.util.List;

/**
 * Contract search, market data subscription and prices
 */
@Tag(name = "Contracts and market data")
@RestController
public class ContractController {

    private final ContractManagerService contractManagerService;
    private final TradingSessionService tradingSessionService;

    ContractController(ContractManagerService contractManagerService, TradingSessionService tradingSessionService) {
        this.contractManagerService = contractManagerService;
        this.tradingSessionService = tradingSessionService;
    }

    @Operation(summary = "Search for an instrument by it's ticker, or part of it's name.")
    @GetMapping("/search")
    List<ContractDto> searchContract(@Parameter(description = "Ticker, or name of the traded instrument", examples = {
            @ExampleObject(name = "Ticker", value = "AAPL"),
            @ExampleObject(name = "Company name", value = "Apple")
    }) @RequestParam String query) {
        return contractManagerService.searchContract(query).stream().map(ContractDto::of).toList();
    }

    @Operation(summary = "Subscribes to an instrument by it's conid. Subscription means the TWS starts streaming the "
            + "market data for the instrument which will be saved into Redis TimeSeries (stream:[conid]:[BID|ASK|LAST])")
    @GetMapping("/subscribe/{conid}")
    ContractDto subscribeMarketDataByConid(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid) {
        contractManagerService.subscribeMarketData(conid);
        return ContractDto.of(contractManagerService.getContractHolder(conid));
    }

    @Operation(summary = "Returns the contract descriptor with its details. Unknown conids are resolved through TWS.")
    @GetMapping("/contract/{conid}")
    ContractDto getContractByConid(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid) {
        return ContractDto.of(contractManagerService.getContractHolder(conid));
    }

    @Operation(summary = "Trading session of the instrument: open, next_open and next_close (UTC), computed from "
            + "the liquid_hours (regular trading hours, default) or the trading_hours (hours=trading, with the "
            + "extended hours) and the time zone of the exchange.")
    @GetMapping("/contract/{conid}/session")
    TradingSession getTradingSession(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid,
                                     @Parameter(description = "liquid (regular trading hours) or trading (with the extended hours)")
                                     @RequestParam(defaultValue = "liquid") @Pattern(regexp = "liquid|trading") String hours) {
        return tradingSessionService.getSession(conid, "trading".equals(hours));
    }

    @Operation(summary = "Returns the last available bid, ask and last price by conid. Without a market data "
            + "subscription it subscribes, and waits at most IBKR_PRICE_QUOTE_WAIT (3s) for the first quote. The "
            + "prices are null if no tick arrived yet. Check stale / quote_age_seconds: the price may be old, eg. "
            + "outside of the trading hours. With max_age an older price is rejected with 422 STALE_MARKET_DATA.")
    @GetMapping("/price/{conid}")
    MarketPrice getLastPriceByConid(@Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid,
                                    @Parameter(description = "Maximum age of the bid/ask in seconds")
                                    @RequestParam(name = "max_age", required = false) @Positive Integer maxAge) {
        return contractManagerService.getLastPriceByConid(conid, seconds(maxAge));
    }

    @Operation(summary = "Returns the last available bid, ask and last price for the given contract (only the conid "
            + "is used). Works like GET /price/{conid}.")
    @PostMapping("/price")
    MarketPrice getLastPriceByContract(@RequestBody ContractDto contract,
                                       @Parameter(description = "Maximum age of the bid/ask in seconds")
                                       @RequestParam(name = "max_age", required = false) @Positive Integer maxAge) {
        return contractManagerService.getLastPriceByConid(contract.conid(), seconds(maxAge));
    }

    @Operation(summary = "Returns the option chain for an underlying instrument. This method won't subscribe to the "
            + "market data of the options.")
    @GetMapping("/optionChain/{underlyingConid}")
    Collection<Option> getOptionChain(@Parameter(description = "The conid (IBKR unique id) of the underlying instrument") @PathVariable int underlyingConid) {
        return contractManagerService.getOptionChainByConid(underlyingConid);
    }

    private static Duration seconds(Integer seconds) {
        return seconds == null ? null : Duration.ofSeconds(seconds);
    }
}
