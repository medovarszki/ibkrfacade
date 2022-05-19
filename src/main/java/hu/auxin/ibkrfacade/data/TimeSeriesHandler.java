package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.redislabs.redistimeseries.RedisTimeSeries;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class TimeSeriesHandler {

    @Value("${redis.default-retention}")
    private int DEFAULT_RETENTION;

    private RedisTimeSeries rts;

    public TimeSeriesHandler(@Autowired RedisProperties redisProperties) {
        String password = StringUtils.hasText(redisProperties.getPassword()) ? redisProperties.getPassword() : null;
        this.rts = new RedisTimeSeries(redisProperties.getHost(), redisProperties.getPort(), redisProperties.getTimeout().toMillisPart(), 3, password);
    }

    public RedisTimeSeries getInstance() {
        return rts;
    }

    /**
     * Creates two different timeseries in Redis. One is for the ask and one is for the bid prices. Both series are labeled with the conid of the Contract
     * @param streamRequestId
     * @param contract
     */
    public void createStream(int streamRequestId, Contract contract) {
        rts.create("stream:" + streamRequestId + ":BID", DEFAULT_RETENTION, Map.of("side", TickType.BID.name(), "conid", Integer.toString(contract.conid())));
        rts.create("stream:" + streamRequestId + ":ASK", DEFAULT_RETENTION, Map.of("side", TickType.ASK.name(), "conid", Integer.toString(contract.conid())));
    }

    public long addToStream(int tickerId, double value, TickType tickType) {
        return rts.add("stream:" + tickerId + ":" + tickType.name(), value);
    }

}
