package hu.auxin.ibkrfacade.data.holder;

import lombok.Data;

@Data
public class PositionHolder {

    private ContractHolder contractHolder;

    private OrderHolder openOrder;

    private OrderHolder closeOrder;

    private double openPrice;

    private double closePrice;
}
