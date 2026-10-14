package hu.auxin.ibkrfacade.web;

import hu.auxin.ibkrfacade.order.OrderResponse;
import hu.auxin.ibkrfacade.order.OrderResult;
import hu.auxin.ibkrfacade.order.OrderService;
import hu.auxin.ibkrfacade.order.PlaceOrderRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;

@Tag(name = "Orders")
@RestController
public class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(summary = "Places an order (requires the write token).",
            description = "Idempotent by client_order_id: a repeated request with the same client_order_id returns "
                    + "the existing order (HTTP 200, duplicate=true) and never places a new one. Reusing a "
                    + "client_order_id with different parameters is rejected with 409. Without a recent bid/ask of "
                    + "the instrument the order is rejected with 422 MARKET_DATA_UNAVAILABLE.")
    @ApiResponse(responseCode = "201", description = "Order placed")
    @ApiResponse(responseCode = "200", description = "Order with the same client_order_id already exists")
    @ApiResponse(responseCode = "400", description = "Invalid request")
    @ApiResponse(responseCode = "403", description = "LIVE_TRADING_DISABLED")
    @ApiResponse(responseCode = "404", description = "CONTRACT_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "CLIENT_ORDER_ID_REUSED")
    @ApiResponse(responseCode = "422", description = "MARKET_DATA_UNAVAILABLE")
    @ApiResponse(responseCode = "503", description = "TWS_NOT_CONNECTED, ACCOUNT_MODE_UNKNOWN, IDEMPOTENCY_STORE_UNAVAILABLE")
    @PostMapping("/order")
    ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderResult result = orderService.place(request);
        if (!result.created()) {
            return ResponseEntity.ok(result.order());
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.CREATED);
        if (result.order().orderId() != null) {
            response.location(ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path("/orders/{orderId}").buildAndExpand(result.order().orderId()).toUri());
        }
        return response.body(result.order());
    }

    @Operation(summary = "Returns the orders of the day (open, completed and the ones placed by the facade), "
            + "or the order with the given client_order_id (a list with 0 or 1 element).")
    @GetMapping("/orders")
    List<OrderResponse> getOrders(@Parameter(description = "Client side order id (IBKR orderRef)")
                                  @RequestParam(name = "client_order_id", required = false) String clientOrderId) {
        if (clientOrderId != null) {
            try {
                return List.of(orderService.getByClientOrderId(clientOrderId));
            } catch (ApiException e) {
                if (e.getStatus() == HttpStatus.NOT_FOUND) {
                    return List.of();
                }
                throw e;
            }
        }
        return orderService.getAll(false);
    }

    @Operation(summary = "Returns the active (working) orders.")
    @GetMapping("/orders/active")
    List<OrderResponse> getActiveOrders() {
        return orderService.getAll(true);
    }

    @Operation(summary = "Returns an order by the IBKR order id: status, filled quantity, average fill price, "
            + "commission and executions.")
    @GetMapping("/orders/{orderId}")
    OrderResponse getOrder(@PathVariable int orderId) {
        return orderService.getByOrderId(orderId);
    }

    @Operation(summary = "Requests the cancellation of an active order (requires the write token).")
    @ApiResponse(responseCode = "202", description = "Cancellation requested, see the status in the response")
    @ApiResponse(responseCode = "404", description = "ORDER_NOT_FOUND")
    @ApiResponse(responseCode = "409", description = "ORDER_NOT_ACTIVE")
    @DeleteMapping("/orders/{orderId}")
    ResponseEntity<OrderResponse> cancelOrder(@PathVariable int orderId) {
        return ResponseEntity.accepted().body(orderService.cancel(orderId));
    }
}
