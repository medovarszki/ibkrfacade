package hu.auxin.ibkrfacade.service;

import com.ib.client.*;
import hu.auxin.ibkrfacade.data.holder.OrderHolder;
import hu.auxin.ibkrfacade.twssample.OrderSamples;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    private static final Logger LOG = LogManager.getLogger(OrderManagerService.class);

    int orderId = 100; //TODO nextOrderId?

    private Map<Integer, OrderHolder> orders = new HashMap<>();

    @NonNull
    private EClientSocket client;

    /**
     * Placing an order on the market
     *
     * @param contract
     * @param action
     * @param quantity
     * @param price
     */
    public void placeOrder(Contract contract, Types.Action action, double quantity, double price) {
        Order order = OrderSamples.LimitOrder(action.getApiString(), quantity, price);
        client.placeOrder(orderId++, contract, order);
    }

    /**
     * Order created, get from IB and store
     * @param contract
     * @param order
     * @param orderState
     */
    public void setOrder(Contract contract, Order order, OrderState orderState) {
        orders.put(order.permId(), new OrderHolder(order.permId(), order, contract, orderState));
    }

    /**
     * Order status changed
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

    public void setClient(EClientSocket client) {
        this.client = client;
    }
}
