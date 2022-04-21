package hu.auxin.ibkrfacade.strategy;

import hu.auxin.ibkrfacade.data.redis.ContractRepository;
import hu.auxin.ibkrfacade.data.redis.TimeSeriesHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TradingStrategy {

    private static final Logger LOG = LogManager.getLogger(TradingStrategy.class);

    private TimeSeriesHandler timeSeriesHandler;
    private ContractRepository contractRepository;

    public TradingStrategy(@Autowired TimeSeriesHandler timeSeriesHandler, @Autowired ContractRepository contractRepository) {
        this.timeSeriesHandler = timeSeriesHandler;
        this.contractRepository = contractRepository;
    }

    @Scheduled(fixedRate = 60*1000)
    private void checkTradingSignal() {
        LOG.info("Scheduled method call");
    }
}