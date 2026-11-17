package hu.auxin.ibkrfacade.web;

import com.ib.client.Types;
import hu.auxin.ibkrfacade.options.OptionChain;
import hu.auxin.ibkrfacade.options.OptionChainService;
import hu.auxin.ibkrfacade.options.OptionProduct;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Options with live quotes
 */
@Tag(name = "Contracts and market data")
@RestController
public class OptionController {

    private final OptionChainService optionChainService;

    OptionController(OptionChainService optionChainService) {
        this.optionChainService = optionChainService;
    }

    @Operation(summary = "Options expiring together with a VX future, with conids, live bid/ask and the IBKR model "
            + "implied volatility and greeks.",
            description = """
                    product=VIX (default): the monthly VIX index options (OPT, trading class VIX), which settle to \
                    the same SOQ as the VX future; their last trading day is the day before the expiration of the \
                    future. product=VX_FOP: options on the VX future (FOP on CFE), 404 FOP_NOT_AVAILABLE if IBKR \
                    has no such contracts.

                    Only the max_strikes strikes closest to the price of the future (mid, or last, or previous \
                    close) within F * (1 - strike_range) and F * (1 + strike_range) are returned, each with the \
                    requested rights. The contracts are cached by expiration (IBKR_OPTION_CHAIN_CACHE_TTL, 12h).

                    Every option is subscribed to market data, and the request waits at most IBKR_PRICE_QUOTE_WAIT \
                    (3s) altogether for the first quotes of the new subscriptions. Each option uses a market data \
                    line of the account: at most IBKR_OPTION_MAX_SUBSCRIPTIONS (60) options are kept subscribed \
                    by this endpoint, and when a chain needs more, the subscriptions of the least recently \
                    requested chain are cancelled first. A single request needing more lines is rejected with 400 \
                    OPTION_SUBSCRIPTION_LIMIT.

                    The model_* values are the IBKR option model (tickOptionComputation). TWS sends them \
                    asynchronously, so they may be null in the first response of a new subscription (and outside of \
                    the trading hours they may take longer).

                    Missing market data permissions do not fail the request: the option has stream_error (eg. \
                    354) and stale=true. 404 CONTRACT_NOT_FOUND for an unknown conid, 400 INVALID_FUTURE if it's \
                    not a VIX future, 404 OPTIONS_NOT_FOUND if there are no options for its expiration, 422 \
                    UNDERLYING_PRICE_UNAVAILABLE without any price of the future.""")
    @GetMapping("/options/chain")
    OptionChain getOptionChain(
            @Parameter(description = "Conid of the VX future", example = "866999753")
            @RequestParam(name = "future_conid") @Positive int futureConid,
            @Parameter(description = "VIX: VIX index options, VX_FOP: options on the VX future")
            @RequestParam(defaultValue = "VIX") OptionProduct product,
            @Parameter(description = "Strikes within F * (1 - strike_range) and F * (1 + strike_range)")
            @RequestParam(name = "strike_range", defaultValue = "0.25")
            @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") double strikeRange,
            @Parameter(description = "Number of strikes closest to the price of the future")
            @RequestParam(name = "max_strikes", defaultValue = "15") @Min(1) @Max(100) int maxStrikes,
            @Parameter(description = "Comma separated rights: C (call), P (put)", example = "C,P")
            @RequestParam(defaultValue = "C,P") @Size(min = 1, max = 2) List<@Pattern(regexp = "[CP]") String> rights) {
        Set<Types.Right> parsed = EnumSet.noneOf(Types.Right.class);
        rights.forEach(right -> parsed.add(Types.Right.get(right)));
        return optionChainService.getChain(futureConid, product, strikeRange, maxStrikes, parsed);
    }
}
