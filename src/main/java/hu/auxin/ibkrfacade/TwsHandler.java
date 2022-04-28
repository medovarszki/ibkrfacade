package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;

import java.util.List;

public interface TwsHandler {

    void subscribeMarketData(Contract contract, boolean tickData);

    List<Contract> searchContract(String search);

    ContractDetails requestContractDetails(Contract contract);
}
