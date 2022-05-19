package hu.auxin.ibkrfacade.service;

import com.ib.client.*;
import hu.auxin.ibkrfacade.data.holder.OrderHolder;
import hu.auxin.ibkrfacade.twssample.OrderSamples;
import org.springframework.context.annotation.Scope;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Scope("singleton")
public class OrderManagerService {

    private int orderId = 100;

    private Map<Integer, OrderHolder> orders = new HashMap<>();

    @NonNull
    private EClientSocket client;

    /**
     * Place an order on the market for a Contract
     *
     * @param contract
     * @param order
     */
    public void placeOrder(Contract contract, Order order) {
        client.placeOrder(orderId++, contract, order);
    }

    public void placeLimitOrder(Contract contract, Types.Action action, double quantity, double limitPrice) {
        Order order = OrderSamples.LimitOrder(action.getApiString(), quantity, limitPrice);
        client.placeOrder(orderId++, contract, order);
    }

    public void placeMarketOrder(Contract contract, Types.Action action, double quantity) {
        Order order = OrderSamples.MarketOrder(action.getApiString(), quantity);
        client.placeOrder(orderId++, contract, order);
    }

    public void placeStopLimitOrder(Contract contract, Types.Action action, double quantity, double stopPrice, double limitPrice) {
        Order order = OrderSamples.StopLimit(action.getApiString(), quantity, limitPrice, stopPrice);
        client.placeOrder(orderId++, contract, order);
    }

    /**
     * Order created, get from IB and store
     *
     * @param contract
     * @param order
     * @param orderState
     */
    public void setOrder(Contract contract, Order order, OrderState orderState) {
        orders.put(order.permId(), new OrderHolder(order.permId(), order, contract, orderState));
    }

    /**
     * Order status changed
     *
     * @param permId
     * @param status
     * @param filled
     * @param remaining
     * @param avgFillPrice
     * @param lastFillPrice
     */
    public void changeOrderStatus(int permId, String status, double filled, double remaining, double avgFillPrice, double lastFillPrice) {
        OrderHolder orderHolder = orders.get(permId);
        if(orderHolder != null) {
            orderHolder.getOrderState().status(status);
            orderHolder.getOrder().filledQuantity(filled);
        } else {
            throw new RuntimeException("Order empty for permId=" + permId);
        }
    }

    public Collection<OrderHolder> getAllOrders() {
        return orders.values();
    }

    public Collection<OrderHolder> getActiveOrders() {
        return orders.values().stream()
                .filter(orderHolder -> orderHolder.getOrderState().status().isActive())
                .collect(Collectors.toList());
    }

    public Collection<OrderHolder> getAllOrdersByContract(Contract contract) {
        return orders.values().stream()
                .filter(orderHolder -> orderHolder.getContract().conid() == contract.conid())
                .collect(Collectors.toList());
    }

    public Collection<OrderHolder> getActiveOrdersByContract(Contract contract) {
        return orders.values().stream()
                .filter(orderHolder -> orderHolder.getContract().conid() == contract.conid())
                .filter(orderHolder -> orderHolder.getOrderState().status().isActive())
                .collect(Collectors.toList());
    }

    public void setOrderId(int orderId) {
        this.orderId = orderId;
    }

    public void setClient(EClientSocket client) {
        this.client = client;
    }
}
