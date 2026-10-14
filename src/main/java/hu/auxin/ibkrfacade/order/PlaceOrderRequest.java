package hu.auxin.ibkrfacade.order;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "New order. The client_order_id is an idempotency key: a request with an already used "
        + "client_order_id never places a new order, it returns the existing one.")
public record PlaceOrderRequest(
        @JsonProperty("client_order_id")
        @Schema(description = "Client side unique id of the order, stored in the IBKR orderRef field", example = "auxin-20261014-0001", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]+") String clientOrderId,

        @Schema(description = "IBKR contract id", example = "265598", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Positive Integer conid,

        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull OrderSide action,

        @Schema(example = "10", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Positive BigDecimal quantity,

        @JsonProperty("order_type")
        @Schema(description = "Defaults to LMT") OrderKind orderType,

        @JsonProperty("limit_price")
        @Schema(description = "Required for LMT and STP_LMT orders") @Positive Double limitPrice,

        @JsonProperty("stop_price")
        @Schema(description = "Required for STP and STP_LMT orders") @Positive Double stopPrice,

        @Schema(description = "Defaults to DAY") TimeInForce tif,

        @JsonProperty("outside_rth")
        @Schema(description = "Allow the order to be filled outside of the regular trading hours. Defaults to false") Boolean outsideRth,

        @Schema(description = "Account id, optional if the gateway manages a single account") @Size(max = 32) String account) {

    public OrderKind effectiveOrderType() {
        return orderType == null ? OrderKind.LMT : orderType;
    }

    public TimeInForce effectiveTif() {
        return tif == null ? TimeInForce.DAY : tif;
    }

    public boolean effectiveOutsideRth() {
        return Boolean.TRUE.equals(outsideRth);
    }

    /**
     * Canonical form of the order parameters, used to detect the reuse of a client_order_id with different parameters.
     */
    public String fingerprint() {
        return String.join("|", String.valueOf(conid), String.valueOf(action), quantity.stripTrailingZeros().toPlainString(),
                effectiveOrderType().name(), String.valueOf(limitPrice), String.valueOf(stopPrice),
                effectiveTif().name(), String.valueOf(effectiveOutsideRth()), String.valueOf(account));
    }
}
