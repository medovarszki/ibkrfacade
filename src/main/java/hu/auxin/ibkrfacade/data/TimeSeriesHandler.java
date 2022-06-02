package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import com.ib.client.TickType;
import com.redislabs.redistimeseries.RedisTimeSeries;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Proxy class for handling the time series data stored in Redis. You can use either the methods of this,
 * or just get the RedisTimeSeries instance itself by getInstance().
 *
 * @see <a href="https://github.com/RedisTimeSeries/JRedisTimeSeries">JRedisTimeSeries</a>
 */
@Component
@DependsOnDatabaseInitialization
public class TimeSeriesHandler {

    @Value("${redis.default-retention}")
    private int DEFAULT_RETENTION;

    /**
     * Redis Time Series handler class from JRedisTimeSeries. You can access it from anywhere via getInstance()
     */
    private RedisTimeSeries rts;

    public TimeSeriesHandler(@Autowired RedisProperties redisProperties) {
        String password = StringUtils.hasText(redisProperties.getPassword()) ? redisProperties.getPassword() : null;
        this.rts = new RedisTimeSeries(redisProperties.getHost(), redisProperties.getPort(), redisProperties.getTimeout().toMillisPart(), 3, password);
    }

    /**
     * Access the JRedisTimeSeries instance itself.
     *
     * @return RedisTimeSeries instance
     */
    public RedisTimeSeries getInstance() {
        return rts;
    }

    /**
     * Creates two different timeseries in Redis. One is for the ask and one is for the bid prices.
     * Both series are labeled with the conid of the Contract
     *
     * @param streamRequestId
     * @param contract
     */
    public void createStream(int streamRequestId, Contract contract) {
        rts.create("stream:" + streamRequestId + ":BID", DEFAULT_RETENTION, Map.of("side", TickType.BID.name(), "conid", Integer.toString(contract.conid())));
        rts.create("stream:" + streamRequestId + ":ASK", DEFAULT_RETENTION, Map.of("side", TickType.ASK.name(), "conid", Integer.toString(contract.conid())));
    }

    /**
     * Adds a new data point to the time series stored in Redis
     *
     * @param streamRequestId is the key parameter which identifies the data stream in TWS.
     * @param value           price
     * @param tickType        BID/ASK
     * @return
     */
    public long addToStream(int streamRequestId, double value, TickType tickType) {
        return rts.add("stream:" + streamRequestId + ":" + tickType.name(), value);
    }

}
