package hu.auxin.ibkrfacade.strategy;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.ib.client.Types;
import com.redislabs.redistimeseries.Value;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
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
import java.text.DecimalFormat;
import java.util.Date;
import java.util.Optional;

/**
 * For testing purpose ONLY!
 */
@Component
@Scope("singleton")
public class TrendTradingStrategy {

    private boolean active = false;
    private static final Logger LOG = LogManager.getLogger(TrendTradingStrategy.class);

    private TimeSeriesHandler timeSeriesHandler;
    private ContractRepository contractRepository;
    private OrderManagerService orderManagerService;

    private String redisKey;
    private int conid = 265598;  //AAPL
    private Contract apple;

    private DecimalFormat df = new DecimalFormat("0.00000000");

    public TrendTradingStrategy(@Autowired OrderManagerService orderManagerService,
                                @Autowired TimeSeriesHandler timeSeriesHandler,
                                @Autowired ContractRepository contractRepository) {
        this.orderManagerService = orderManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    @PostConstruct
    private void init() {
    }

    @Scheduled(fixedRate = 5*1000, initialDelay = 10000)
    private synchronized void checkForTradingSignal() {
        if(!active) {
            Optional<ContractHolder> contractHolder = contractRepository.findById(conid);
            if(contractHolder.isPresent()) {
                apple = contractHolder.get().getContract();
                redisKey = "stream:" + contractHolder.get().getStreamRequestId();
                active = true; //if we have a stream, set it to true
            }
            return;
        }
        if(orderManagerService.getActiveOrders() != null) {
            LOG.info("Currently we have an open position"); //TODO manage position
        } else {
            long now = new Date().getTime();

            Value[] bidArray = timeSeriesHandler.getInstance().range(redisKey + ":" + TickType.BID, now - (5 * 60 * 1000), now);

            if(bidArray.length > 50) { //we have at least 50 price data
                double lastPrice = bidArray[bidArray.length].getValue();
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
                    orderManagerService.placeOrder(apple, Types.Action.BUY, 10, lastPrice);
                }
            } else {
                LOG.warn("No data points");
            }
        }
    }
}
