package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import com.ib.client.TickType;

import jakarta.validation.constraints.NotNull;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.timeseries.DuplicatePolicy;
import redis.clients.jedis.timeseries.TSCreateParams;

/**
 * Proxy class for handling the time series data stored in Redis. You can use
 * either the methods of this,
 * or just get the RedisTimeSeries instance itself by getInstance().
 *
 * @see <a href=
 *      "https://github.com/RedisTimeSeries/JRedisTimeSeries">JRedisTimeSeries</a>
 */
@Component
public class TimeSeriesHandler {

    @Value("${redis.default-retention}")
    @NotNull
    private final int DEFAULT_RETENTION = 3600000;

    public static String STREAM_STRING = "stream:";

    /**
     * Redis Time Series handler class from JRedisTimeSeries. You can access it from
     * anywhere via getInstance()
     */
    private JedisPooled jedisPooled;

    /**
     * Access the Jedis instance from the connection pool.
     *
     * @return Jedis instance
     */
    public JedisPooled getInstance() {
        return jedisPooled;
    }

    public TimeSeriesHandler(@Autowired RedisProperties redisProperties) {
        jedisPooled = new JedisPooled(redisProperties.getHost(), redisProperties.getPort(), "default",
                redisProperties.getPassword());
    }

    /**
     * Creates two different timeseries in Redis. One is for the ask and one is for
     * the bid prices.
     * Both series are labeled with the conid of the Contract
     *
     * @param streamRequestId
     * @param contract
     */
    public void createStream(int streamRequestId, Contract contract) {
        String bidKey = STREAM_STRING + streamRequestId + ":BID";
        String askKey = STREAM_STRING + streamRequestId + ":ASK";

        TSCreateParams paramsBid = new TSCreateParams()
                .retention(DEFAULT_RETENTION)
                .label("side", TickType.BID.name())
                .label("conid", Integer.toString(contract.conid()))
                .chunkSize(3600)
                .duplicatePolicy(DuplicatePolicy.LAST);

        jedisPooled.tsCreate(bidKey, paramsBid);

        TSCreateParams paramsAsk = new TSCreateParams()
                .retention(DEFAULT_RETENTION)
                .label("side", TickType.BID.name())
                .label("conid", Integer.toString(contract.conid()))
                .chunkSize(3600)
                .duplicatePolicy(DuplicatePolicy.LAST);

        jedisPooled.tsCreate(askKey, paramsAsk);

    }

    /**
     * Adds a new data point to the time series stored in Redis
     *
     * @param streamRequestId is the key parameter which identifies the data stream
     *                        in TWS.
     * @param value           price
     * @param tickType        BID/ASK
     * @return
     */
    public long addToStream(int streamRequestId, double value, TickType tickType) {
        return jedisPooled.tsAdd(STREAM_STRING + streamRequestId + ":" + tickType.name(), System.currentTimeMillis(),
                value);
    }

}
