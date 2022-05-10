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
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.text.DecimalFormat;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * For testing purpose ONLY!
 */
@Component
@Scope("singleton")
public class TrendTradingStrategy {

    private static final Logger LOG = LogManager.getLogger(TrendTradingStrategy.class);

    private TimeSeriesHandler timeSeriesHandler;
    private ContractRepository contractRepository;
    private OrderManagerService orderManagerService;
    private PositionManagerService positionManagerService;
    private ContractManagerService contractManagerService;

    private int conid = 265598;  //AAPL
    private ContractHolder apple;

    private DecimalFormat df = new DecimalFormat("0.00000000");

    public TrendTradingStrategy(@Autowired OrderManagerService orderManagerService,
                                @Autowired PositionManagerService positionManagerService,
                                @Autowired TimeSeriesHandler timeSeriesHandler,
                                @Autowired ContractManagerService contractManagerService,
                                @Autowired ContractRepository contractRepository) {
        this.orderManagerService = orderManagerService;
        this.positionManagerService = positionManagerService;
        this.contractManagerService = contractManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    @PostConstruct
    private void init() {
    }

    @Scheduled(fixedRate = 5, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    private synchronized void checkForTradingSignal() {
        if(apple == null) {
            //TODO on startup
            Contract apple = contractManagerService.getContractByConid(conid);
            contractManagerService.subscribeMarketData(apple);
            Optional<ContractHolder> contractHolder = contractRepository.findById(conid);
            if(contractHolder.isPresent()) {
                this.apple = contractHolder.get();
            }
            return;
        }
        if(!orderManagerService.getActiveOrdersByContract(apple.getContract()).isEmpty()) {
            LOG.info("Currently we have an open order");
        } else if(positionManagerService.getPositionByContract(apple.getContract()) != null) {
            LOG.info("We have an open position, don't check for buy signal");
        } else {
            long now = new Date().getTime();

            Value[] bidArray = timeSeriesHandler.getInstance().range("stream:" + apple.getStreamRequestId() + ":" + TickType.BID, now - (5 * 60 * 1000), now);

            if(bidArray.length > 100 && bidArray[bidArray.length-1].getTime() > new Date().getTime() - 5000) { //at least 5 minutes of data
                double lastPrice = bidArray[bidArray.length-1].getValue();
                double[] prices = new double[bidArray.length];

                SimpleRegression regression = new SimpleRegression();

                int i = 0;
                for(Value current : bidArray) {
                    regression.addData(current.getTime(), current.getValue());
                    prices[i++] = current.getValue();
                }

                double slope = regression.getSlope();
                double variance = new Variance().evaluate(prices);

                LOG.info("Slope is: {}; Variance: {}; last price: {})", df.format(slope), df.format(variance), lastPrice);

                if(slope > 0.5 && variance < 0.03) { //TODO finding working parameters
                    LOG.info("BUY SIGNAL! - Last price: {}", lastPrice);
                    orderManagerService.placeLimitOrder(apple.getContract(), Types.Action.BUY, 10, lastPrice);
                } else if(slope < -0.5 && variance < 0.03) {
                    LOG.info("SHORT SIGNAL! - Last price: {}", lastPrice);
                }
            } else {
                LOG.warn("No data points");
            }
        }
    }

    @Scheduled(fixedRate = 1, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    private synchronized void handleClose() {
        PositionHolder positionHolder = positionManagerService.getPositionByContract(apple.getContract());
        if(positionHolder != null) {
            boolean isShort = positionHolder.getQuantity() < 0;
            PriceHolder lastPrice = contractManagerService.getLastPriceByContract(apple.getContract());
            if(lastPrice.getBid() != null) {
                if(orderManagerService.getActiveOrdersByContract(apple.getContract()).isEmpty()) { //no active order
                    if(lastPrice.getBid() < positionHolder.getAvgPrice() - 1) { //TODO define a proper threshold
                        orderManagerService.placeLimitOrder(apple.getContract(), isShort ? Types.Action.BUY : Types.Action.SELL, positionHolder.getQuantity(), lastPrice.getBid()); // can be a market order
                        return;
                    }
                } else {

                }
            }
        }
    }
}
