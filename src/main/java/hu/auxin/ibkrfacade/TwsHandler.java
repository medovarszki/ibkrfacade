package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import hu.auxin.ibkrfacade.data.OrderData;

import java.util.List;

public interface TwsHandler {

    void subscribeMarketData(Contract contract, boolean tickData);

    List<OrderData> getOrders();

    List<Contract> searchContract(String search);

    ContractDetails requestContractDetails(Contract contract);
}
