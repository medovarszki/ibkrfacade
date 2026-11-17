package hu.auxin.ibkrfacade.options;

import com.fasterxml.jackson.annotation.JsonProperty;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.marketdata.OptionGreeks;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Options expiring together with a VX future, with their latest quotes, ordered by strike "
        + "and right (C before P).")
public record OptionChain(
        @JsonProperty("future_conid") @Schema(description = "Conid of the VX future") int futureConid,
        @Schema(description = "Last trading day of the options (YYYYMMDD) as reported by IBKR. For the VIX index "
                + "options it is the day before the expiration of the future.", example = "20261215") String expiration,
        @Schema(description = "Contract multiplier of the options, null if unknown", example = "100") Integer multiplier,
        @JsonProperty("trading_class") @Schema(example = "VIX") String tradingClass,
        @JsonProperty("underlying_price") @Schema(description = "Price of the future used for the strike filter: "
                + "mid, or last, or previous close if there is no bid/ask") Double underlyingPrice,
        List<Quote> options) {

    @Schema(description = "Option with its latest quote. The prices and sizes are null if TWS sent no such tick.")
    public record Quote(
            @Schema(description = "IBKR contract id of the option") int conid,
            double strike,
            @Schema(description = "C (call) or P (put)") String right,
            Double bid,
            Double ask,
            @JsonProperty("bid_size") BigDecimal bidSize,
            @JsonProperty("ask_size") BigDecimal askSize,
            Double last,
            @JsonProperty("quote_age_seconds") @Schema(description = "Age of the latest bid or ask in whole seconds, "
                    + "null if there is no quote") Long quoteAgeSeconds,
            @Schema(description = "True if there is no bid or ask, or the latest one is older than the staleness "
                    + "threshold (IBKR_PRICE_STALE_AFTER)") boolean stale,
            @JsonProperty("model_iv") @Schema(description = "Implied volatility of the IBKR option model "
                    + "(tickOptionComputation), eg. 0.82; null until TWS sends it") Double modelIv,
            @JsonProperty("model_delta") Double modelDelta,
            @JsonProperty("model_gamma") Double modelGamma,
            @JsonProperty("model_vega") Double modelVega,
            @JsonProperty("model_theta") Double modelTheta,
            @JsonProperty("stream_error") @Schema(description = "Last error reported by TWS for the market data of "
                    + "the option (eg. 354: no market data subscription), or the reason why it is not subscribed")
            String streamError) {

        static Quote of(int conid, double strike, String right, MarketPrice price, OptionGreeks greeks,
                        String streamError) {
            return new Quote(conid, strike, right, price.bid(), price.ask(), price.bidSize(), price.askSize(),
                    price.last(), price.quoteAgeSeconds(), price.stale(), greeks.impliedVol(), greeks.delta(),
                    greeks.gamma(), greeks.vega(), greeks.theta(), streamError);
        }
    }
}
