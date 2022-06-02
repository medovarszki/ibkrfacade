package hu.auxin.ibkrfacade.strategy;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.ib.client.Types;
import com.redislabs.redistimeseries.Value;
import hu.auxin.ibkrfacade.data.ContractRepository;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Sample trading strategy, don't even think about using it real conditions!
 */
@Slf4j
@Component
@DependsOn("TWS")
@Scope("singleton")
public class TrendTradingStrategy {

    private TimeSeriesHandler timeSeriesHandler;
    private ContractRepository contractRepository;
    private OrderManagerService orderManagerService;
    private PositionManagerService positionManagerService;
    private ContractManagerService contractManagerService;

    private int conid = 265598;  //AAPL
    private ContractHolder apple;

    @Autowired
    public TrendTradingStrategy(OrderManagerService orderManagerService, PositionManagerService positionManagerService,
                                TimeSeriesHandler timeSeriesHandler, ContractManagerService contractManagerService,
                                ContractRepository contractRepository) {
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.contractManagerService = contractManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    @PostConstruct
    private void init() {
        Contract apple = contractManagerService.getContractByConid(conid);
        contractManagerService.subscribeMarketData(apple);
        Optional<ContractHolder> contractHolder = contractRepository.findById(conid);
        if(contractHolder.isPresent()) {
            this.apple = contractHolder.get();
        }
    }

    @Scheduled(fixedRate = 10, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    private synchronized void checkForTradingSignal() {
        if(apple == null) {
            return;
        }

        if(!orderManagerService.getActiveOrdersByContract(apple.getContract()).isEmpty()) {
            log.info("We already have an open order");
        } else if(positionManagerService.getPositionByContract(apple.getContract()) != null) {
            log.info("We already have an open position");
        } else {
            long now = new Date().getTime();
            long sampleLength = 60 * 1000; // take 1 minutes of data

            Value[] bidArray = timeSeriesHandler.getInstance().range("stream:" + apple.getStreamRequestId() + ":" + TickType.BID, now - sampleLength, now);

            if(bidArray.length > 10 && bidArray[0].getTime() > now - sampleLength) {
                double lastPrice = bidArray[bidArray.length - 1].getValue();
                double changePercentage = ((lastPrice / bidArray[0].getValue()) - 1) * 100;
                log.info("Change in time window: {}%", changePercentage);
                if(Math.abs(changePercentage) > 0.05) { // rate of change in selected time window must be at least
                    log.info("BUY ORDER");
                    orderManagerService.placeMarketOrder(apple.getContract(), Types.Action.BUY, 10);
                }
            }
        }
    }

    @Scheduled(fixedRate = 5, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    private synchronized void handleClose() {
        PositionHolder positionHolder = positionManagerService.getPositionByContract(apple.getContract());
        if(positionHolder != null) {
            // we have an open position
            boolean isShort = positionHolder.getQuantity() < 0;
            PriceHolder lastPrice = contractManagerService.getLastPriceByContract(apple.getContract());

            double maxChange = positionHolder.getAvgPrice() * 0.01; //max allowed loss (0.25%)
            double stopLoss = isShort ? positionHolder.getAvgPrice() + maxChange : positionHolder.getAvgPrice() - maxChange;

            if(orderManagerService.getActiveOrdersByContract(apple.getContract()).isEmpty()) {
                // no active order
                if(lastPrice.getBid() < stopLoss) {
                    // close position on market price, when threshold reached
                    orderManagerService.placeMarketOrder(apple.getContract(), isShort ? Types.Action.BUY : Types.Action.SELL, positionHolder.getQuantity());
                } else if(lastPrice.getBid() > positionHolder.getAvgPrice() + maxChange) {
                    // we have some unrealized gains, set a stop loss
                    double activationPrice = lastPrice.getBid() - stopLoss;
                    double limitPrice = lastPrice.getBid() - stopLoss - 0.5;
                    orderManagerService.placeStopLimitOrder(apple.getContract(), isShort ? Types.Action.BUY : Types.Action.SELL, positionHolder.getQuantity(), activationPrice, limitPrice);
                }
            } else {
                // already have an active order
            }
        }
    }
}
