package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.history.HistoryResponse;
import hu.auxin.ibkrfacade.history.HistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Contracts and market data")
@RestController
public class HistoryController {

    private final HistoryService historyService;

    HistoryController(HistoryService historyService) {
        this.historyService = historyService;
    }

    @Operation(summary = "Historical OHLCV bars (reqHistoricalData). Subject to the IBKR historical data pacing "
            + "limits and market data permissions.")
    @GetMapping("/history/{conid}")
    HistoryResponse getHistory(
            @Parameter(description = "The conid (IBKR unique id) of the instrument") @PathVariable int conid,
            @Parameter(description = "Period: <number> <S|D|W|M|Y>", example = "1 M") @RequestParam(defaultValue = "1 M") String duration,
            @Parameter(description = "Bar size, eg. '1 min', '5 mins', '1 hour', '1 day'", example = "1 day") @RequestParam(defaultValue = "1 day") String barSize,
            @Parameter(description = "TRADES, MIDPOINT, BID, ASK, BID_ASK, ADJUSTED_LAST, ...", example = "TRADES") @RequestParam(defaultValue = "TRADES") String whatToShow,
            @Parameter(description = "Regular trading hours only") @RequestParam(defaultValue = "true") boolean useRth,
            @Parameter(description = "End of the period in UTC (yyyyMMdd-HH:mm:ss), defaults to now", example = "20261014-20:00:00") @RequestParam(required = false) String endDateTime) {
        return historyService.getHistory(conid, duration, barSize, whatToShow, useRth, endDateTime);
    }
}
