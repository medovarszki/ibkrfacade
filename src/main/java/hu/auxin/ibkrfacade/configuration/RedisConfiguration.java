package hu.auxin.ibkrfacade.configuration;

import com.ib.client.Decimal;
import org.springframework.boot.data.redis.autoconfigure.DataRedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.redis.core.convert.RedisCustomConversions;
import org.springframework.util.StringUtils;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.UnifiedJedis;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Low level Redis client used for the RedisTimeSeries and the order idempotency commands. The Spring Data repositories
 * use the auto-configured connection factory, both are configured from the same spring.data.redis.* properties.
 */
@Configuration
public class RedisConfiguration {

    @Bean(destroyMethod = "close")
    UnifiedJedis unifiedJedis(DataRedisProperties properties) {
        DefaultJedisClientConfig.Builder config = DefaultJedisClientConfig.builder()
                .database(properties.getDatabase());
        if (StringUtils.hasLength(properties.getPassword())) {
            config.user(StringUtils.hasLength(properties.getUsername()) ? properties.getUsername() : "default")
                    .password(properties.getPassword());
        }
        Duration timeout = properties.getTimeout();
        if (timeout != null) {
            config.timeoutMillis((int) timeout.toMillis());
        }
        return RedisClient.builder()
                .hostAndPort(properties.getHost(), properties.getPort())
                .clientConfig(config.build())
                .build();
    }

    /**
     * The TWS API Decimal type (eg. in ContractDetails) has no default constructor, so it's stored as a string
     * in the Redis hashes of the repositories.
     */
    @Bean
    RedisCustomConversions redisCustomConversions() {
        return new RedisCustomConversions(List.of(new DecimalToBytes(), new BytesToDecimal()));
    }

    @WritingConverter
    static class DecimalToBytes implements Converter<Decimal, byte[]> {
        @Override
        public byte[] convert(Decimal source) {
            return source.toString().getBytes(StandardCharsets.UTF_8);
        }
    }

    @ReadingConverter
    static class BytesToDecimal implements Converter<byte[], Decimal> {
        @Override
        public Decimal convert(byte[] source) {
            return Decimal.parse(new String(source, StandardCharsets.UTF_8));
        }
    }
}
