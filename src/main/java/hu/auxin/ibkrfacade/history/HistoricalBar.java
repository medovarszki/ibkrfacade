package hu.auxin.ibkrfacade.history;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "OHLCV bar")
public record HistoricalBar(
        @Schema(description = "Start of the bar: ISO-8601 date-time with the offset of the exchange time zone for "
                + "intraday bars (eg. 2026-10-14T09:30-04:00), ISO-8601 date for daily and longer bars", example = "2026-10-14T09:30:00-04:00")
        String time,
        @Schema(description = "Start of the bar as epoch milliseconds (daily bars: midnight in the exchange time zone)")
        long timestamp,
        double open,
        double high,
        double low,
        double close,
        BigDecimal volume,
        @Schema(description = "Volume weighted average price") BigDecimal wap,
        @Schema(description = "Number of trades") int count) {
}
