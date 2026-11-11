package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.marketdata.SpreadQuote;
import hu.auxin.ibkrfacade.marketdata.SpreadService;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.web.dto.ContractDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Futures chain, prices of several instruments and calendar spreads
 */
@Tag(name = "Contracts and market data")
@RestController
public class MarketDataController {

    private final ContractManagerService contractManagerService;
    private final SpreadService spreadService;

    MarketDataController(ContractManagerService contractManagerService, SpreadService spreadService) {
        this.contractManagerService = contractManagerService;
        this.spreadService = spreadService;
    }

    @Operation(summary = "Futures of an underlying ordered by expiration, eg. symbol=VIX, exchange=CFE, "
            + "trading_class=VX for the monthly VIX futures. underlying_conid is the conid of the underlying index.")
    @GetMapping("/futures/chain")
    List<ContractDto> getFuturesChain(
            @Parameter(description = "Symbol of the underlying", example = "VIX") @RequestParam @NotBlank String symbol,
            @Parameter(description = "Exchange of the futures", example = "CFE") @RequestParam @NotBlank String exchange,
            @Parameter(description = "Trading class, eg. VX for the monthly VIX futures (the weekly ones are VX01..VX53)", example = "VX")
            @RequestParam(name = "trading_class", required = false) String tradingClass,
            @Parameter(example = "USD") @RequestParam(required = false) String currency,
            @Parameter(description = "With the expired contracts (TWS knows the ones expired in the last two years)")
            @RequestParam(name = "include_expired", defaultValue = "false") boolean includeExpired) {
        return contractManagerService.getFuturesChain(symbol, exchange, currency, tradingClass, includeExpired)
                .stream().map(ContractDto::of).toList();
    }

    @Operation(summary = "Latest prices of several instruments in the order of the conids, like GET /price/{conid}. "
            + "The ones without a market data subscription are subscribed, and the request waits at most "
            + "IBKR_PRICE_QUOTE_WAIT (3s) altogether for their first quotes. Every subscription uses a market "
            + "data line of the account.")
    @GetMapping("/prices")
    List<MarketPrice> getPrices(@Parameter(description = "Comma separated conids, at most 100", example = "265598,272093")
                                @RequestParam @Size(min = 1, max = 100) List<@Positive Integer> conids) {
        return contractManagerService.getLastPrices(conids);
    }

    @Operation(summary = "Calendar spread of two futures (far - near): the spread computed from the bid/ask of the "
            + "legs, and the quote of the exchange listed spread. Only the most recently requested spreads "
            + "(IBKR_SPREAD_MAX_SUBSCRIPTIONS, 10) keep their market data subscription.")
    @GetMapping("/spread")
    SpreadQuote getSpread(@Parameter(description = "Conid of the near (sold) leg") @RequestParam @Positive int near,
                          @Parameter(description = "Conid of the far (bought) leg") @RequestParam @Positive int far) {
        return spreadService.getSpread(near, far);
    }
}
