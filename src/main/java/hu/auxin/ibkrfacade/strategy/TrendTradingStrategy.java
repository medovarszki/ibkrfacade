package hu.auxin.ibkrfacade.strategy;

import com.ib.client.TickType;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.holder.PriceHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.service.PositionManagerService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import redis.clients.jedis.timeseries.TSElement;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
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
    private OrderManagerService orderManagerService;
    private PositionManagerService positionManagerService;
    private ContractManagerService contractManagerService;

    private final int conid = 265598; // AAPL
    private ContractHolder apple;

    public TrendTradingStrategy(OrderManagerService orderManagerService, PositionManagerService positionManagerService,
            TimeSeriesHandler timeSeriesHandler, ContractManagerService contractManagerService) {
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.contractManagerService = contractManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
    }

    @PostConstruct
    private void init() {
        this.apple = contractManagerService.getContractHolder(conid);
        int streamId = contractManagerService.subscribeMarketData(apple.getContract());
        this.apple.setStreamRequestId(streamId); // already saved to the contract in Redis, but here we need to add
                                                 // because the stream was started later than the request
    }

//    @Scheduled(cron = "0/10 * 9-16 * * ?", zone = "America/New_York") // job will run every 10 seconds (0/10) during the hours of 9am to 4pm
    private synchronized void checkForTradingSignal() {
        if (apple == null) {
            return;
        }

        if (!orderManagerService.getActiveOrdersByContract(apple.getContract()).isEmpty()) {
            log.info("We already have an open order");
        } else if (positionManagerService.getPositionByContract(apple.getContract()) != null) {
            log.info("We already have an open position");
        } else {
            long now = new Date().getTime();
            long sampleLength = 60 * 1000; // take 1 minutes of data

            try {
                java.util.List<TSElement> bidArray = timeSeriesHandler.getInstance()
                        .tsRange(("stream:" + apple.getStreamRequestId() + ":" + TickType.BID), now - sampleLength,
                                now);

                TSElement firstTsElement = bidArray.get(0);
                TSElement lastTsElement = bidArray.get(bidArray.size() - 1);
                if (bidArray.size() > 10 && firstTsElement.getTimestamp() > now - sampleLength) {
                    double changePercentage = ((lastTsElement.getValue() / firstTsElement.getValue()) - 1) * 100;
                    log.info("Change in time window: {}%", changePercentage);
                    if (Math.abs(changePercentage) > 0.05) { // rate of change in selected time window must be at least
                        log.info("BUY ORDER");
                        orderManagerService.placeMarketOrder(
                                contractManagerService.getContractHolder(conid).getContract(), Types.Action.BUY, 10);
                    }
                }
            } catch (Exception e) {
                log.error("no data for Apple as yet", e);
            }
        }
    }

    @Scheduled(fixedRate = 5, initialDelay = 15, timeUnit = TimeUnit.SECONDS)
    private synchronized void handleClose() {
        PositionHolder positionHolder = positionManagerService.getPositionByContract(apple.getContract());
        if (positionHolder != null) {
            // we have an open position
            boolean isShort = positionHolder.getQuantity() < 0;
            PriceHolder lastPrice = contractManagerService.getLastPriceByContract(apple.getContract());

            double maxChange = positionHolder.getAvgPrice() * 0.01; // max allowed loss (0.25%)
            double stopLoss = isShort ? positionHolder.getAvgPrice() + maxChange
                    : positionHolder.getAvgPrice() - maxChange;

            if (orderManagerService.getActiveOrdersByContract(apple.getContract()).isEmpty()) {
                // no active order
                if (lastPrice.getBid() < stopLoss) {
                    // close position on market price, when threshold reached
                    orderManagerService.placeMarketOrder(apple.getContract(),
                            isShort ? Types.Action.BUY : Types.Action.SELL, positionHolder.getQuantity());
                } else if (lastPrice.getBid() > positionHolder.getAvgPrice() + maxChange) {
                    // we have some unrealized gains, set a stop loss
                    double activationPrice = lastPrice.getBid() - stopLoss;
                    double limitPrice = lastPrice.getBid() - stopLoss - 0.5;
                    orderManagerService.placeStopLimitOrder(apple.getContract(),
                            isShort ? Types.Action.BUY : Types.Action.SELL, positionHolder.getQuantity(),
                            activationPrice, limitPrice);
                }
            } else {
                // already have an active order
            }
        }
    }
}
