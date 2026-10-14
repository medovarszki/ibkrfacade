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
        @JsonProperty("stream_error") @Schema(description = "Last error reported by TWS for the market data subscription, eg. missing market data permission") String streamError) {

    /**
     * Time of the most recent bid or ask, null if there is no quote at all.
     */
    @JsonProperty("quote_time")
    public Instant quoteTime() {
        if (bidTime == null) {
            return askTime;
        }
        if (askTime == null) {
            return bidTime;
        }
        return bidTime.isAfter(askTime) ? bidTime : askTime;
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
}
