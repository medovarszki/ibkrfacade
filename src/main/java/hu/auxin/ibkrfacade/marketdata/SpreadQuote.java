package hu.auxin.ibkrfacade.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Calendar spread of two futures: far leg minus near leg. Buying the spread is buying the far "
        + "and selling the near leg.")
public record SpreadQuote(
        @JsonProperty("near_conid") int nearConid,
        @JsonProperty("far_conid") int farConid,
        @Schema(description = "Price of the near leg") MarketPrice near,
        @Schema(description = "Price of the far leg") MarketPrice far,
        @Schema(description = "Spread computed from the quotes of the legs") Synthetic synthetic,
        @JsonProperty("native") @Schema(description = "Quote of the exchange listed spread (combo) book") Native nativeQuote) {

    @Schema(description = "Spread computed from the legs, null values if a leg has no such price")
    public record Synthetic(
            @Schema(description = "Price the spread can be sold at: far bid - near ask") Double bid,
            @Schema(description = "Price the spread can be bought at: far ask - near bid") Double ask,
            @Schema(description = "(bid + ask) / 2") Double mid,
            @Schema(description = "far last - near last, the two trades may be from different times") Double last,
            @Schema(description = "far close - near close: the spread of the previous settlement prices") Double close,
            @Schema(description = "True if the quote of any leg is stale") boolean stale) {

        public static Synthetic of(MarketPrice near, MarketPrice far) {
            Double bid = diff(far.bid(), near.ask());
            Double ask = diff(far.ask(), near.bid());
            Double mid = bid == null || ask == null ? null : MarketPrice.round((bid + ask) / 2);
            return new Synthetic(bid, ask, mid, diff(far.last(), near.last()), diff(far.close(), near.close()),
                    near.stale() || far.stale());
        }

        private static Double diff(Double far, Double near) {
            return far == null || near == null ? null : MarketPrice.round(far - near);
        }
    }

    @Schema(description = "Quote of the exchange listed spread. The prices may be negative or zero.")
    public record Native(
            Double bid,
            Double ask,
            @Schema(description = "(bid + ask) / 2, null without both sides") Double mid,
            Double last,
            @Schema(description = "Previous close of the spread, null if unknown") Double close,
            @JsonProperty("bid_size") BigDecimal bidSize,
            @JsonProperty("ask_size") BigDecimal askSize,
            @JsonProperty("last_size") BigDecimal lastSize,
            BigDecimal volume,
            @JsonProperty("bid_time") Instant bidTime,
            @JsonProperty("ask_time") Instant askTime,
            @JsonProperty("last_time") Instant lastTime,
            @JsonProperty("quote_age_seconds") @Schema(description = "Age of the latest bid or ask in whole seconds, null if there is no quote") Long quoteAgeSeconds,
            @Schema(description = "True if there is no bid or ask, or the latest one is older than the staleness threshold") boolean stale,
            @Schema(description = "True if the facade has an active market data subscription for the spread") boolean subscribed,
            @JsonProperty("stream_error") @Schema(description = "Last error reported by TWS for the subscription, or the reason why there is none") String streamError,
            @JsonProperty("market_data_type") MarketDataType marketDataType) {

        public static Native unavailable(String reason) {
            return new Native(null, null, null, null, null, null, null, null, null, null, null, null, null, true,
                    false, reason, null);
        }
    }
}
