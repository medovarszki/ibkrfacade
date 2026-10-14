package hu.auxin.ibkrfacade.data.holder;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Represents an open position")
public record PositionHolder(
        @Schema(description = "Account id") String account,
        @Schema(description = "IBKR contract id") int conid,
        String symbol,
        @JsonProperty("sec_type") String secType,
        String currency,
        @Schema(description = "Size of the position. In case of short position, the value is negative.") BigDecimal quantity,
        @JsonProperty("avg_cost") @Schema(description = "Average cost of the position (for derivatives it includes the multiplier).") double avgCost,
        @JsonProperty("market_price") @Schema(description = "Latest market price reported by the portfolio update, null until the first update arrives.") Double marketPrice,
        @JsonProperty("market_value") Double marketValue,
        @JsonProperty("unrealized_pnl") Double unrealizedPnl,
        @JsonProperty("realized_pnl") Double realizedPnl,
        @JsonProperty("updated_at") Instant updatedAt) {
}
