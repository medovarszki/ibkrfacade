package hu.auxin.ibkrfacade.order;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Stores the reservations in Redis with SET NX under ibkr:order:client:[client_order_id]
 */
@Component
public class RedisIdempotencyStore implements IdempotencyStore {

    static final String KEY_PREFIX = "ibkr:order:client:";

    private final UnifiedJedis jedis;
    private final Duration ttl;

    public RedisIdempotencyStore(UnifiedJedis jedis, @Value("${ibkr.order.idempotency-ttl:30d}") Duration ttl) {
        this.jedis = jedis;
        this.ttl = ttl;
    }

    @Override
    public boolean reserve(OrderReservation reservation) {
        String value = reservation.orderId() + "\n" + reservation.reservedAt().toEpochMilli() + "\n"
                + reservation.fingerprint();
        String result = jedis.set(KEY_PREFIX + reservation.clientOrderId(), value,
                SetParams.setParams().nx().ex(ttl.toSeconds()));
        return "OK".equals(result);
    }

    @Override
    public Optional<OrderReservation> find(String clientOrderId) {
        String value = jedis.get(KEY_PREFIX + clientOrderId);
        if (value == null) {
            return Optional.empty();
        }
        String[] parts = value.split("\n", 3);
        return Optional.of(new OrderReservation(clientOrderId, Integer.parseInt(parts[0]),
                parts.length > 2 ? parts[2] : "", Instant.ofEpochMilli(Long.parseLong(parts[1]))));
    }
}
