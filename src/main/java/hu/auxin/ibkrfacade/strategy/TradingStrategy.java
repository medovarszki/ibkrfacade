package hu.auxin.ibkrfacade.strategy;

import com.ib.client.TickType;
import com.redislabs.redistimeseries.Value;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.redis.ContractRepository;
import hu.auxin.ibkrfacade.data.redis.TimeSeriesHandler;
import org.apache.commons.math3.stat.descriptive.moment.Variance;
import org.apache.commons.math3.stat.regression.SimpleRegression;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Component
@Scope("singleton")
public class TradingStrategy {

    private static final Logger LOG = LogManager.getLogger(TradingStrategy.class);

    private TimeSeriesHandler timeSeriesHandler;
    private ContractRepository contractRepository;

    private PositionHolder positionHolder;

    private String redisKey;

    public TradingStrategy(@Autowired TimeSeriesHandler timeSeriesHandler, @Autowired ContractRepository contractRepository) {
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    @PostConstruct
    private void init() {
        Optional<ContractHolder> contractHolder = contractRepository.findById(265598);
        Integer requestId = contractHolder.orElseThrow(() -> new RuntimeException("No contract found in Redis with conid 265598")).getStreamRequestId();
        if(requestId != null) {
            redisKey = "stream:" + requestId;
        }
        redisKey = "stream:0";
    }

    @Scheduled(fixedRate = 5*1000, initialDelay = 10000)
    private synchronized void checkForTradingSignal() {
        LocalDateTime start = LocalDateTime.now().minus(1, ChronoUnit.YEARS);
        // TS.RANGE stream:0:ASK 1651261423622 1651261930643
        Value[] askArray = timeSeriesHandler.getInstance().range(redisKey + ":" + TickType.ASK, start.toEpochSecond(ZoneOffset.UTC), LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));
        Value[] bidArray = timeSeriesHandler.getInstance().range(redisKey + ":" + TickType.BID, 1651261423622L, 1651261930643L);
        List<Value> bids = List.of(bidArray);

        int i = 0;
        double[] values = new double[bids.size()]; //used for variance caluculation
        SimpleRegression regression = new SimpleRegression();
        for(Value current : bids) {
            regression.addData(current.getTime(), current.getValue());
            values[i++] = current.getValue();
        }

        double slope = regression.getSlope();

        Variance varianceObj = new Variance();
        double variance = varianceObj.evaluate(values);

        LOG.info("Slope is: {}; Variance: {}", slope, variance);
    }

    private void openPosition() {
    }

    private void maintainClose() {
    }
}
