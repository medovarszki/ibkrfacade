package hu.auxin.ibkrfacade.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "A (partial) fill of an order")
public record ExecutionRecord(
        @JsonProperty("exec_id") String execId,
        @Schema(description = "Execution time as reported by TWS") String time,
        @Schema(description = "BOT or SLD") String side,
        @Schema(description = "Filled quantity of this execution") BigDecimal shares,
        @Schema(description = "Execution price") double price,
        String exchange,
        @Schema(description = "Commission and fees of this execution, null until the commission report arrives") Double commission,
        @JsonProperty("commission_currency") String commissionCurrency,
        @JsonProperty("realized_pnl") Double realizedPnl) {

    ExecutionRecord withCommission(Double commission, String commissionCurrency, Double realizedPnl) {
        return new ExecutionRecord(execId, time, side, shares, price, exchange, commission, commissionCurrency, realizedPnl);
    }
}
