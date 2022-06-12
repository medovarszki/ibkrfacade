package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;

import java.util.Collection;
import java.util.List;

/**
 * Publicly available methods for TWS communication
 */
public interface TwsHandler {

    void subscribeMarketData(Contract contract, boolean tickData);

    List<Contract> searchContract(String search);

    Contract requestContractByConid(int conid);

    ContractDetails requestContractDetails(Contract contract);

    Collection<Contract> requestForOptionChain(Contract contract);
}
