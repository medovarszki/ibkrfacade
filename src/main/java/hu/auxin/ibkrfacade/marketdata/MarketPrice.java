package hu.auxin.ibkrfacade.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Duration;
import java.time.Instant;

@Schema(description = "Latest known bid/ask/last prices of an instrument from the market data stream.")
public record MarketPrice(
        @Schema(description = "IBKR contract id") Integer conid,
        @Schema(description = "Latest bid, null if unknown") Double bid,
        @Schema(description = "Latest ask, null if unknown") Double ask,
        @Schema(description = "Latest trade price, null if unknown") Double last,
        @JsonProperty("bid_time") Instant bidTime,
        @JsonProperty("ask_time") Instant askTime,
        @JsonProperty("last_time") Instant lastTime,
        @Schema(description = "True if the facade has an active market data subscription for the instrument") boolean subscribed,
        @JsonProperty("stream_error") @Schema(description = "Last error reported by TWS for the market data subscription, eg. missing market data permission") String streamError,
        @JsonProperty("market_data_type") @Schema(description = "Type of the market data sent by TWS for the "
                + "subscription, null if unknown (no subscription, or tick-by-tick stream)") MarketDataType marketDataType,
        @JsonProperty("quote_age_seconds") @Schema(description = "Age of the latest bid or ask (quote_time) in "
                + "whole seconds at the time of the response, null if there is no quote") Long quoteAgeSeconds,
        @Schema(description = "True if there is no bid or ask, or the latest one is older than the staleness "
                + "threshold (IBKR_PRICE_STALE_AFTER, default IBKR_ORDER_MAX_QUOTE_AGE = 15m)") boolean stale) {

    /**
     * Computes the age of the quote and the stale flag at the given time.
     *
     * @param staleAfter a quote older than this is stale; zero or negative disables the age check
     */
    public static MarketPrice of(Integer conid, Double bid, Double ask, Double last, Instant bidTime, Instant askTime,
                                 Instant lastTime, boolean subscribed, String streamError,
                                 MarketDataType marketDataType, Instant now, Duration staleAfter) {
        Instant quoteTime = latest(bidTime, askTime);
        Long age = quoteTime == null ? null : Math.max(0, Duration.between(quoteTime, now).toSeconds());
        boolean stale = quoteTime == null
                || (staleAfter.isPositive() && quoteTime.isBefore(now.minus(staleAfter)));
        return new MarketPrice(conid, bid, ask, last, bidTime, askTime, lastTime, subscribed, streamError,
                marketDataType, age, stale);
    }

    /**
     * Time of the most recent bid or ask, null if there is no quote at all.
     */
    @JsonProperty("quote_time")
    public Instant quoteTime() {
        return latest(bidTime, askTime);
    }

    /**
     * True if there is a bid or ask which is not older than maxAge. A zero or negative maxAge disables the age check.
     */
    public boolean hasQuote(Duration maxAge, Instant now) {
        Instant quoteTime = quoteTime();
        if (quoteTime == null) {
            return false;
        }
        return maxAge.isZero() || maxAge.isNegative() || !quoteTime.isBefore(now.minus(maxAge));
    }

    private static Instant latest(Instant first, Instant second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }
}
