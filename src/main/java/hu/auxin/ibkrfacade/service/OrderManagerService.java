package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.OrderState;
import hu.auxin.ibkrfacade.data.OrderData;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Scope("singleton")
public class OrderManagerService {

    private Map<Integer, OrderData> orders = new HashMap<>();

    public void setOrder(Contract contract, Order order, OrderState orderState) {
        orders.put(order.permId(), new OrderData(order.permId(), order, contract, orderState));
    }

    public void changeOrderStatus(int permId, String status) {
        orders.get(permId).getState().status(status);
    }

    public Collection<OrderData> getAllOrders() {
        return orders.values();
    }

    public Collection<OrderData> getActiveOrders() {
        return orders.values().stream().filter(orderData -> orderData.getState().status().isActive()).collect(Collectors.toList());
    }
}
