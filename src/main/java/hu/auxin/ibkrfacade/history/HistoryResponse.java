package hu.auxin.ibkrfacade.history;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Historical bars of an instrument")
public record HistoryResponse(
        int conid,
        String symbol,
        String duration,
        @JsonProperty("bar_size") String barSize,
        @JsonProperty("what_to_show") String whatToShow,
        @JsonProperty("use_rth") boolean useRth,
        @JsonProperty("time_zone") @Schema(description = "Time zone of the exchange, the bar times are in this zone", example = "US/Eastern") String timeZone,
        List<HistoricalBar> bars) {
}
