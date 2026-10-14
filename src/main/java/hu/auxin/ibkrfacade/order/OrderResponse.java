package hu.auxin.ibkrfacade.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "State of an order")
public record OrderResponse(
        @JsonProperty("order_id") @Schema(description = "IBKR order id (unique per API client id)") Integer orderId,
        @JsonProperty("perm_id") @Schema(description = "IBKR permanent order id, 0 until TWS assigns it") Long permId,
        @JsonProperty("client_order_id") @Schema(description = "Client side idempotency key, stored in the IBKR orderRef field") String clientOrderId,
        String account,
        Integer conid,
        String symbol,
        @JsonProperty("sec_type") String secType,
        String currency,
        @Schema(description = "BUY or SELL") String action,
        @JsonProperty("order_type") String orderType,
        BigDecimal quantity,
        @JsonProperty("limit_price") Double limitPrice,
        @JsonProperty("stop_price") Double stopPrice,
        String tif,
        @JsonProperty("outside_rth") boolean outsideRth,
        @Schema(description = "IBKR order status (ApiPending, PendingSubmit, PreSubmitted, Submitted, PendingCancel, "
                + "Cancelled, ApiCancelled, Filled, Inactive) or one of the facade statuses: Rejected (TWS rejected "
                + "the order before acknowledging it), Unknown (the order was reserved, but it's not known by TWS)")
        String status,
        @Schema(description = "True while the order is working on the market") boolean active,
        @JsonProperty("filled_quantity") BigDecimal filledQuantity,
        @JsonProperty("remaining_quantity") BigDecimal remainingQuantity,
        @JsonProperty("avg_fill_price") Double avgFillPrice,
        @Schema(description = "Sum of the commissions and fees of the executions, null if no report arrived yet") Double commission,
        @JsonProperty("commission_currency") String commissionCurrency,
        @JsonProperty("realized_pnl") Double realizedPnl,
        List<ExecutionRecord> executions,
        @JsonProperty("error_code") Integer errorCode,
        @JsonProperty("error_message") String errorMessage,
        @Schema(description = "Warnings and informational messages sent by TWS for the order") List<String> warnings,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @Schema(description = "True if the response belongs to an earlier request with the same client_order_id "
                + "(no new order was placed)") boolean duplicate) {
}
