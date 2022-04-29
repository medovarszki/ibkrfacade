package hu.auxin.ibkrfacade.data.redis;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.redislabs.redistimeseries.RedisTimeSeries;
import hu.auxin.ibkrfacade.data.dto.PriceData;
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

    public void createStream(int key, Contract contract) {
        rts.create("stream:" + key + ":BID", DEFAULT_RETENTION, Map.of("side", TickType.BID.name(), "conid", String.valueOf(contract.conid())));
        rts.create("stream:" + key + ":ASK", DEFAULT_RETENTION, Map.of("side", TickType.ASK.name(), "conid", String.valueOf(contract.conid())));
    }

    public long addToStream(int tickerId, double value, TickType tickType) {
        return rts.add("stream:" + tickerId + ":" + tickType.name(), value);
    }

    public PriceData getLatestPrice(int tickerId) {
        double bid = rts.get("stream:" + tickerId + ":" + TickType.BID.name()).getValue();
        double ask = rts.get("stream:" + tickerId + ":" + TickType.ASK.name()).getValue();
        return new PriceData(bid, ask);
    }

}
