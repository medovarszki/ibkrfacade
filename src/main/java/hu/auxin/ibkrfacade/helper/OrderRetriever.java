package hu.auxin.ibkrfacade.helper;

import com.ib.client.Contract;
import com.ib.client.Order;
import com.ib.client.OrderState;
import hu.auxin.ibkrfacade.data.OrderData;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

public class OrderRetriever {

    @Getter
    private List<OrderData> orders;

    @Getter
    private boolean done = false;

    public OrderRetriever() {
        this.orders = new ArrayList<>();
    }
    public void addOrder(Order order, Contract contract, OrderState orderState) {
        this.orders.add(new OrderData(order.permId(), order, contract, orderState));
    }

    public void release() {
        this.done = true;
    }
}
