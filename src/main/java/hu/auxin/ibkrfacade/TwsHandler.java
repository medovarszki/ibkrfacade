package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Order;

import java.util.List;

public interface TwsHandler {

    void subscribeMarketData(Contract contract, boolean tickData);

    List<Order> getOrders();

    List<Contract> searchContract(String search);

    ContractDetails requestContractDetails(Contract contract);
}
