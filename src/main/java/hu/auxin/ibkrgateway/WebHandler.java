package hu.auxin.ibkrgateway;

import com.ib.client.Contract;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class WebHandler {

    @Autowired
    private TwsClient tws;

    @GetMapping("/search")
    public List<Contract> searchContract(@RequestParam String query) {
        return tws.searchContract(query);
    }

    @PostMapping("/subscribe")
    public Contract subscribeToContract(@RequestBody Contract contract) {
        tws.subscribeToContract(contract);
        return contract;
    }
}
