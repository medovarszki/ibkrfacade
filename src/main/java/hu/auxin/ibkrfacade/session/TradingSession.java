package hu.auxin.ibkrfacade.session;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Trading session of an instrument, computed from the liquid_hours (regular trading hours) or "
        + "trading_hours (including the extended hours) and the time_zone of the contract details.")
public record TradingSession(
        @Schema(description = "IBKR contract id") int conid,
        @Schema(description = "liquid (regular trading hours) or trading (including the extended hours)") String hours,
        @JsonProperty("time_zone") @Schema(description = "Time zone of the exchange, eg. US/Eastern") String timeZone,
        @Schema(description = "True if the market is open at as_of") boolean open,
        @JsonProperty("next_open") @Schema(description = "Start of the next session after as_of (the one after the "
                + "current session, if open), null if unknown (TWS sends about one week ahead)") Instant nextOpen,
        @JsonProperty("next_close") @Schema(description = "End of the current session if open, otherwise end of the "
                + "next session; null if unknown") Instant nextClose,
        @JsonProperty("as_of") @Schema(description = "Time of the evaluation") Instant asOf) {
}
