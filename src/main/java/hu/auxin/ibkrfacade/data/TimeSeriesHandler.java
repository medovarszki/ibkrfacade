package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import com.ib.client.TickType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.timeseries.DuplicatePolicy;
import redis.clients.jedis.timeseries.TSCreateParams;
import redis.clients.jedis.timeseries.TSElement;

/**
 * Proxy class for handling the time series data stored in Redis. You can use
 * either the methods of this, or just get the Redis client itself by getInstance().
 *
 * @see <a href="https://redis.io/docs/latest/develop/data-types/timeseries/">RedisTimeSeries</a>
 */
@Component
public class TimeSeriesHandler {

    public static final String STREAM_STRING = "stream:";

    private final long retention;

    /**
     * Redis client (with RedisTimeSeries commands). You can access it from anywhere via getInstance()
     */
    private final UnifiedJedis jedis;

    public TimeSeriesHandler(UnifiedJedis jedis, @Value("${redis.default-retention:3600000}") long retention) {
        this.jedis = jedis;
        this.retention = retention;
    }

    /**
     * Access the Redis client.
     *
     * @return Redis client instance
     */
    public UnifiedJedis getInstance() {
        return jedis;
    }

    /**
     * Key of a time series: stream:[conid]:[BID|ASK|LAST]
     */
    public static String key(int conid, TickType tickType) {
        return STREAM_STRING + conid + ":" + tickType.name();
    }

    /**
     * Creates three timeseries in Redis for the bid, ask and last prices of a Contract.
     * All series are labeled with the conid and the symbol of the Contract.
     * Already existing series are left untouched.
     *
     * @param conid
     * @param contract
     */
    public void createStream(int conid, Contract contract) {
        for (TickType side : new TickType[]{TickType.BID, TickType.ASK, TickType.LAST}) {
            String key = key(conid, side);
            if (jedis.exists(key)) {
                continue;
            }
            TSCreateParams params = new TSCreateParams()
                    .retention(retention)
                    .label("side", side.name())
                    .label("conid", Integer.toString(conid))
                    .label("symbol", String.valueOf(contract.symbol()))
                    .chunkSize(4096)
                    .duplicatePolicy(DuplicatePolicy.LAST);
            jedis.tsCreate(key, params);
        }
    }

    /**
     * Adds a new data point to the time series stored in Redis
     *
     * @param conid    identifies the instrument
     * @param value    price
     * @param tickType BID/ASK/LAST
     * @return
     */
    public long addToStream(int conid, double value, TickType tickType) {
        return jedis.tsAdd(key(conid, tickType), System.currentTimeMillis(), value);
    }

    /**
     * Latest data point of a series, or null if the series does not exist or empty.
     */
    public TSElement latest(int conid, TickType tickType) {
        try {
            return jedis.tsGet(key(conid, tickType));
        } catch (JedisDataException e) {
            return null; // the key does not exist
        }
    }
}
