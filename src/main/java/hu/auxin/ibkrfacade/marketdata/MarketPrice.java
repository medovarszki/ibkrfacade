package hu.auxin.ibkrfacade.marketdata;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                + "threshold (IBKR_PRICE_STALE_AFTER, default IBKR_ORDER_MAX_QUOTE_AGE = 15m)") boolean stale,
        @JsonProperty("quote_basis") @Schema(description = "What quote_time, quote_age_seconds and stale are based "
                + "on: BID_ASK, or LAST for instruments without bid/ask (indices)") QuoteBasis quoteBasis,
        @JsonProperty("bid_size") @Schema(description = "Size of the bid, null if unknown") BigDecimal bidSize,
        @JsonProperty("ask_size") @Schema(description = "Size of the ask, null if unknown") BigDecimal askSize,
        @JsonProperty("last_size") @Schema(description = "Size of the latest trade, null if unknown") BigDecimal lastSize,
        @Schema(description = "Volume of the day, null if unknown") BigDecimal volume,
        @JsonProperty("open_interest") @Schema(description = "Open interest (futures, options), null if unknown") BigDecimal openInterest,
        @Schema(description = "Previous close, for futures the previous settlement price; null if unknown") Double close,
        @Schema(description = "Open of the day, null if unknown") Double open,
        @Schema(description = "High of the day, null if unknown") Double high,
        @Schema(description = "Low of the day, null if unknown") Double low) {

    public enum QuoteBasis {
        BID_ASK, LAST
    }

    /**
     * Computes the age of the quote and the stale flag at the given time.
     *
     * @param staleAfter a quote older than this is stale; zero or negative disables the age check
     */
    public static MarketPrice of(Integer conid, Double bid, Double ask, Double last, Instant bidTime, Instant askTime,
                                 Instant lastTime, boolean subscribed, String streamError,
                                 MarketDataType marketDataType, Instant now, Duration staleAfter) {
        return of(conid, bid, ask, last, bidTime, askTime, lastTime, subscribed, streamError, marketDataType, now,
                staleAfter, QuoteBasis.BID_ASK, MarketStats.EMPTY);
    }

    /**
     * @param basis LAST for instruments without bid/ask (indices): the freshness is based on the latest trade
     * @param stats sizes and daily statistics from the market data stream
     */
    public static MarketPrice of(Integer conid, Double bid, Double ask, Double last, Instant bidTime, Instant askTime,
                                 Instant lastTime, boolean subscribed, String streamError,
                                 MarketDataType marketDataType, Instant now, Duration staleAfter, QuoteBasis basis,
                                 MarketStats stats) {
        Instant quoteTime = basis == QuoteBasis.LAST ? lastTime : latest(bidTime, askTime);
        Long age = quoteTime == null ? null : Math.max(0, Duration.between(quoteTime, now).toSeconds());
        boolean stale = quoteTime == null
                || (staleAfter.isPositive() && quoteTime.isBefore(now.minus(staleAfter)));
        return new MarketPrice(conid, bid, ask, last, bidTime, askTime, lastTime, subscribed, streamError,
                marketDataType, age, stale, basis, stats.bidSize(), stats.askSize(), stats.lastSize(), stats.volume(),
                stats.openInterest(), stats.close(), stats.open(), stats.high(), stats.low());
    }

    /**
     * Time of the most recent bid or ask (of the latest trade if the basis is LAST), null if there is no quote at all.
     */
    @JsonProperty("quote_time")
    public Instant quoteTime() {
        return quoteBasis == QuoteBasis.LAST ? lastTime : latest(bidTime, askTime);
    }

    @JsonProperty("mid")
    @Schema(description = "(bid + ask) / 2, null without both sides")
    public Double mid() {
        return bid == null || ask == null ? null : round((bid + ask) / 2);
    }

    @JsonProperty("spread")
    @Schema(description = "ask - bid, null without both sides")
    public Double spread() {
        return bid == null || ask == null ? null : round(ask - bid);
    }

    /**
     * Removes the floating point noise of computed prices (eg. 17.1 - 17.05)
     */
    public static Double round(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
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
