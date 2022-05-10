package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.holder.OrderHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import hu.auxin.ibkrfacade.twssample.ContractSamples;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

/**
 * Web endpoints for accessing the functions
 */
@RestController
public class WebHandler {

    private ContractRepository contractRepository;
    private ContractManagerService contractManagerService;
    private OrderManagerService orderManagerService;
    private PositionManagerService positionManagerService;

    WebHandler(@Autowired ContractRepository contractRepository,
               @Autowired ContractManagerService contractManagerService,
               @Autowired OrderManagerService orderManagerService,
               @Autowired PositionManagerService positionManagerService) {
        this.contractRepository = contractRepository;
        this.contractManagerService = contractManagerService;
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
    }

    @GetMapping("/search")
    public List<Contract> searchContract(@RequestParam String query) {
        return contractManagerService.searchContract(query);
    }

    @GetMapping("/subscribe")
    public ResponseEntity subscribeMarketDataByConid(@RequestParam int conid, @Value("${ibkr.tick-by-tick-stream}") boolean tickByTick) {
        Contract contract = ContractSamples.ByConId();
        contract.conid(conid);
        contractManagerService.subscribeMarketData(contract, tickByTick);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/subscribe")
    public ResponseEntity subscribeMarketDataByContract(@RequestBody Contract contract, @Value("${ibkr.tick-by-tick-stream}") boolean tickByTick) {
        contractManagerService.subscribeMarketData(contract, tickByTick);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/order")
    public void placeOrder(@RequestParam int conid, @RequestParam String action, @RequestParam double quantity, @RequestParam double price) {
        Contract contract = contractRepository.findById(conid)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found in Redis with conid " + conid))
                .getContract();
        orderManagerService.placeLimitOrder(contract, Types.Action.valueOf(action), quantity, price);
    }

    @GetMapping("/orders")
    public Collection<OrderHolder> getAllOrders() {
        return orderManagerService.getAllOrders();
    }

    @GetMapping("/orders/active")
    public Collection<OrderHolder> getActiveOrders() {
        return orderManagerService.getActiveOrders();
    }

    @GetMapping("/positions")
    public Collection<PositionHolder> getAllPositions() {
        return positionManagerService.getAllPositions();
    }

    @PostMapping("/lastPrice")
    public PriceHolder getLastPrice(@RequestBody Contract contract) {
        return contractManagerService.getLastPriceByContract(contract);
    }
}
