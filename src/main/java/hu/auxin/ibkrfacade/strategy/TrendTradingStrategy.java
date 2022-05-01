package hu.auxin.ibkrfacade.strategy;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.ib.client.Types;
import com.redislabs.redistimeseries.Value;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.redis.ContractRepository;
import hu.auxin.ibkrfacade.data.redis.TimeSeriesHandler;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.Date;

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

    private PositionHolder positionHolder;

    private String redisKey;
    private int conid = 265598;  //AAPL
    private Contract apple;

    public TrendTradingStrategy(@Autowired OrderManagerService orderManagerService,
                                @Autowired TimeSeriesHandler timeSeriesHandler,
                                @Autowired ContractRepository contractRepository) {
        this.orderManagerService = orderManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    @PostConstruct
    private void init() {
        ContractHolder contractHolder = contractRepository.findById(265598).orElseThrow(() -> new RuntimeException("No contract found in Redis with conid " + conid));
        if(contractHolder != null) {
            this.apple = contractHolder.getContract();
            redisKey = "stream:" + contractHolder.getStreamRequestId();
        } else {
            redisKey = "stream:0"; //TODO
        }
    }

    @Scheduled(fixedRate = 5*1000, initialDelay = 10000)
    private synchronized void checkForTradingSignal() {
        if(positionHolder != null) {
            LOG.info("Currently we have an open position");
        } else {
            long now = new Date().getTime();
            Value[] bidArray = timeSeriesHandler.getInstance().range(redisKey + ":" + TickType.BID, now - (5 * 60 * 1000), now);
            int i = 0;
            double[] prices = new double[bidArray.length];
            SimpleRegression regression = new SimpleRegression();
            for(Value current : bidArray) {
                regression.addData(current.getTime(), current.getValue());
                prices[i++] = current.getValue();
            }

            double slope = regression.getSlope();
            double variance = new Variance().evaluate(prices);

            if(slope > 1 && variance < 0.2) {
                double lastPrice = prices[prices.length];
                LOG.info("BUY SIGNAL (Slope is: {}; Variance: {}; last price: {})", slope, variance, lastPrice);
                orderManagerService.placeOrder(apple, Types.Action.BUY, 1, lastPrice);
            }
        }
    }
}
