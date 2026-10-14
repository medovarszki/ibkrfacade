package hu.auxin.ibkrfacade.account;

import com.fasterxml.jackson.annotation.JsonProperty;
import hu.auxin.ibkrfacade.tws.AccountMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

@Schema(description = "Account summary (reqAccountSummary). Monetary values are in the base currency of the account.")
public record AccountSummary(
        String account,
        @JsonProperty("account_mode") @Schema(description = "PAPER or LIVE") AccountMode accountMode,
        @Schema(description = "Base currency of the account") String currency,
        @JsonProperty("net_liquidation") @Schema(description = "Net liquidation value (NAV)") Double netLiquidation,
        @JsonProperty("total_cash_value") Double totalCashValue,
        @JsonProperty("available_funds") Double availableFunds,
        @JsonProperty("buying_power") Double buyingPower,
        @JsonProperty("init_margin_req") Double initMarginReq,
        @JsonProperty("maint_margin_req") Double maintMarginReq,
        @JsonProperty("excess_liquidity") Double excessLiquidity,
        @JsonProperty("gross_position_value") Double grossPositionValue,
        @Schema(description = "Excess liquidity as a fraction of the net liquidation value") Double cushion,
        @Schema(description = "Every tag returned by TWS, unparsed") Map<String, String> values,
        @Schema(description = "Time of the query") Instant timestamp) {
}
