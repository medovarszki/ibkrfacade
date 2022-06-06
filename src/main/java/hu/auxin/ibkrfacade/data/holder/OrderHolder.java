package hu.auxin.ibkrfacade.data.holder;

import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.OrderState;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Schema(description = "Holder class for the IBKR Order type with additional information")
@Data
@AllArgsConstructor
public class OrderHolder {

    @Schema(description = "Permanent ID of the order (won't change between sessions, you can use it as a key).")
    private Integer permId;

    @Schema(description = "The order descriptor from the TWS API. It contains all the basic information.", externalDocs = @ExternalDocumentation(url = "https://interactivebrokers.github.io/tws-api/classIBApi_1_1Order.html"))
    private Order order;

    @Schema(description = "The instrument descriptor from the TWS API", externalDocs = @ExternalDocumentation(url = "https://interactivebrokers.github.io/tws-api/classIBApi_1_1Contract.html"))
    private Contract contract;

    @Schema(description = "State of the order", externalDocs = @ExternalDocumentation(url = "https://interactivebrokers.github.io/tws-api/classIBApi_1_1OrderState.html"))
    private OrderState orderState;
}
