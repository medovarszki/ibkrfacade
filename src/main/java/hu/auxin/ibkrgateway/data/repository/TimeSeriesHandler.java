package hu.auxin.ibkrgateway.data.repository;

import com.redislabs.redistimeseries.RedisTimeSeries;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.stereotype.Component;

@Component
public class TimeSeriesHandler {

    private RedisTimeSeries rts;

    public TimeSeriesHandler(@Autowired RedisProperties redisProperties) {
        this.rts = new RedisTimeSeries(redisProperties.getHost(), redisProperties.getPort(), redisProperties.getTimeout().toHoursPart(), 3, redisProperties.getPassword());
    }

    public boolean createStream(String key) {
        return rts.create(key);
    }

    public long addToStream(String key, double value) {
        return rts.add(key, value);
    }

}
