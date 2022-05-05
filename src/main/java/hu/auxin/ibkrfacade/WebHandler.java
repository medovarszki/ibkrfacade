package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.OrderHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import hu.auxin.ibkrfacade.data.redis.ContractRepository;
import hu.auxin.ibkrfacade.data.redis.TimeSeriesHandler;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import hu.auxin.ibkrfacade.twssample.ContractSamples;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Web endpoints for accessing the functions
 */
@RestController
public class WebHandler {

    private ContractRepository contractRepository;

    private TimeSeriesHandler timeSeriesHandler;

    private OrderManagerService orderManagerService;

    private PositionManagerService positionManagerService;

    private TWS tws;

    WebHandler(@Autowired TWS tws,
               @Autowired ContractRepository contractRepository,
               @Autowired OrderManagerService orderManagerService,
               @Autowired PositionManagerService positionManagerService,
               @Autowired TimeSeriesHandler timeSeriesHandler) {
        this.tws = tws;
        this.contractRepository = contractRepository;
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
    }

    @GetMapping("/search")
    public List<Contract> searchContract(@RequestParam String query) {
        return tws.searchContract(query);
    }

    @GetMapping("/subscribe")
    public Contract subscribeMarketDataByConid(@RequestParam int conid, @Value("${ibkr.tick-by-tick-stream}") boolean tickByTick) {
        Contract contract = ContractSamples.ByConId();
        contract.conid(conid);
        tws.subscribeMarketData(contract, tickByTick);
        return contract;
    }

    @PostMapping("/subscribe")
    public Contract subscribeMarketDataByContract(@RequestBody Contract contract, @Value("${ibkr.tick-by-tick-stream}") boolean tickByTick) {
        tws.subscribeMarketData(contract, tickByTick);
        return contract;
    }

    @PostMapping("/order")
    public void placeOrder(@RequestParam int conid, @RequestParam String action, @RequestParam double quantity, @RequestParam double price) {
        Contract contract = contractRepository.findById(conid)
                .orElseThrow(() -> new IllegalArgumentException("Contract not found in Redis with conid " + conid))
                .getContract();
        orderManagerService.placeOrder(contract, Types.Action.valueOf(action), quantity, price);
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
    public Collection<PositionHolder> getPositions() {
        return positionManagerService.getPositions();
    }

    @PostMapping("/lastPrice")
    public PriceHolder getLastPrice(@RequestBody Contract contract, HttpServletResponse res) {
        Optional<ContractHolder> contractHolder = contractRepository.findById(contract.conid());
        if(contractHolder.isPresent()) {
            return timeSeriesHandler.getLatestPrice(contractHolder.get().getStreamRequestId());
        }
        res.setStatus(HttpStatus.NOT_FOUND.value());
        return null;
    }
}
