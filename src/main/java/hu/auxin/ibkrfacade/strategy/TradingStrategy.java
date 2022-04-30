package hu.auxin.ibkrfacade.strategy;

import com.google.common.collect.Iterables;
import com.ib.client.TickType;
import com.redislabs.redistimeseries.Value;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.data.redis.ContractRepository;
import hu.auxin.ibkrfacade.data.redis.TimeSeriesHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
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
    }

    @Scheduled(fixedRate = 5*1000, initialDelay = 10000)
    private void checkForTradingSignal() {
//        LocalDateTime start = LocalDateTime.now().minus(1, ChronoUnit.YEARS);
//        Value[] bidArray = timeSeriesHandler.getInstance().range(redisKey + ":" + TickType.BID, start.toEpochSecond(ZoneOffset.UTC), LocalDateTime.now().toEpochSecond(ZoneOffset.UTC));
//        List<Value> bids = List.of(bidArray);
//        CollectionUtils.lastElement(List.of(bids));
    }

    private void openPosition() {
    }

    private void maintainClose() {
    }
}
