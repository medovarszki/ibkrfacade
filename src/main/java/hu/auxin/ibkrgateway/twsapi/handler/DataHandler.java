package hu.auxin.ibkrgateway.twsapi.handler;

import com.ib.client.Contract;
import com.ib.client.EClientSocket;
import com.ib.client.Types;

import java.util.HashMap;
import java.util.Map;

public class DataHandler {

    private final EClientSocket client;

    public static final Map<Integer, Contract> subscriptions = new HashMap<>();

    public DataHandler(EClientSocket client) {
        this.client = client;
    }

    public void subscribe(Contract contract) {
        int id = subscriptions.size() + 1;
        subscriptions.put(id, contract);
        client.reqMktData(id, contract, Types.SecType.STK == Types.SecType.get(contract.getSecType()) ? "233" : "100,101", false, false, null);
        if(Types.SecType.OPT == Types.SecType.get(contract.getSecType())) {
            DBHandler.getInstance().createOption(id, contract.symbol(), contract.strike(), contract.right().name().charAt(0), contract.lastTradeDateOrContractMonth());
        }
        System.out.println("Subscribed to: \n" + contract);
    }

}
