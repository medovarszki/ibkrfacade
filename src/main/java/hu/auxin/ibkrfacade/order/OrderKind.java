package hu.auxin.ibkrfacade.order;

import com.ib.client.OrderType;

/**
 * Supported order types
 */
public enum OrderKind {
    MKT(OrderType.MKT), LMT(OrderType.LMT), STP(OrderType.STP), STP_LMT(OrderType.STP_LMT);

    private final OrderType orderType;

    OrderKind(OrderType orderType) {
        this.orderType = orderType;
    }

    public OrderType toOrderType() {
        return orderType;
    }

    public boolean needsLimitPrice() {
        return this == LMT || this == STP_LMT;
    }

    public boolean needsStopPrice() {
        return this == STP || this == STP_LMT;
    }
}
