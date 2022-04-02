package hu.auxin.ibkrgateway;

import com.ib.client.Contract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WebHandler {

    @Autowired
    TWS tws;

    @PostMapping("/search")
    public List<Contract> searchContract(String query) {
        tws.searchContract(query);
        //TODO find possible contracts by string
        return null;
    }

    @PostMapping("/subscribe")
    public Contract subscirbeToContract(@RequestBody Contract contract) {

//        Contract stk = new Contract();
//        stk.symbol("TSLA");
//        stk.secType(Types.SecType.STK);
//        stk.currency("USD");
//        stk.exchange("SMART");
//
        tws.subscribeToContract(contract);
        return contract;
    }
}
