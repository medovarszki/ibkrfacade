package hu.auxin.ibkrfacade;

import com.ib.client.Contract;
import hu.auxin.ibkrfacade.data.ContractData;
import hu.auxin.ibkrfacade.data.OrderData;
import hu.auxin.ibkrfacade.data.PriceData;
import hu.auxin.ibkrfacade.data.redis.ContractRepository;
import hu.auxin.ibkrfacade.data.redis.TimeSeriesHandler;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@RestController
public class WebHandler {

    private ContractRepository contractRepository;

    private TimeSeriesHandler timeSeriesHandler;

    private OrderManagerService orderManagerService;

    private TWS tws;

    WebHandler(@Autowired TWS tws,
               @Autowired ContractRepository contractRepository,
               @Autowired OrderManagerService orderManagerService,
               @Autowired TimeSeriesHandler timeSeriesHandler) {
        this.tws = tws;
        this.contractRepository = contractRepository;
        this.orderManagerService = orderManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
    }

    @GetMapping("/search")
    public List<Contract> searchContract(@RequestParam String query) {
        return tws.searchContract(query);
    }

    @PostMapping("/subscribe")
    public Contract subscribeMarketData(@RequestBody Contract contract, @Value("${ibkr.tick-by-tick-stream}") boolean tickByTick) {
        tws.subscribeMarketData(contract, tickByTick);
        return contract;
    }

    @GetMapping("/orders")
    public Collection<OrderData> getAllOrders() {
        return orderManagerService.getAllOrders();
    }

    @GetMapping("/orders/active")
    public Collection<OrderData> getActiveOrders() {
        return orderManagerService.getActiveOrders();
    }

    @PostMapping("/lastPrice")
    public PriceData getLastPrice(@RequestBody Contract contract, HttpServletResponse res) {
        Optional<ContractData> contractData = contractRepository.findById(contract.conid());
        if(contractData.isPresent()) {
            return timeSeriesHandler.getLatestPrice(contractData.get().getRequestId());
        }
        res.setStatus(HttpStatus.NOT_FOUND.value());
        return null;
    }
}
