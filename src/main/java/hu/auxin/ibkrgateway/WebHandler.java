package hu.auxin.ibkrgateway;

import com.ib.client.Contract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WebHandler {

    private TWS tws;

    WebHandler(@Autowired TWS tws) {
        this.tws = tws;
    }

    @GetMapping("/search")
    public List<Contract> searchContract(@RequestParam String query) {
        return tws.searchContract(query);
    }

    @PostMapping("/subscribe")
    public Contract subscribeMarketData(@RequestBody Contract contract) {
        tws.subscribeMarketData(contract);
        return contract;
    }
}
