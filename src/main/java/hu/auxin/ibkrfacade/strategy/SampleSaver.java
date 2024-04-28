package hu.auxin.ibkrfacade.strategy;


import hu.auxin.ibkrfacade.data.TimeSeriesHandler;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.twssample.ConId;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import redis.clients.jedis.timeseries.TSRangeParams;

import java.util.Date;

@Slf4j
@Component
@DependsOn("TWS")
@Scope("singleton")
public class SampleSaver {

    private TimeSeriesHandler timeSeriesHandler;
    private ContractManagerService contractManagerService;

    private ContractHolder spy;

    private long sampleStart;

    public SampleSaver(TimeSeriesHandler timeSeriesHandler, ContractManagerService contractManagerService) {
        this.contractManagerService = contractManagerService;
        this.timeSeriesHandler = timeSeriesHandler;
    }

    @PostConstruct
    private void init() {
        log.info("Start sampling");
        this.spy = contractManagerService.getContractHolder(ConId.SPY);
        int streamId = contractManagerService.subscribeMarketData(spy.getContract());
        this.spy.setStreamRequestId(streamId);
        this.sampleStart = new Date().getTime();
        // already saved to the contract in Redis, but here we need to add because the stream was started later than the request

        timeSeriesHandler.getInstance().tsRange("stream:" + streamId + ":BID", TSRangeParams.rangeParams().fromTimestamp(sampleStart));
    }
}
