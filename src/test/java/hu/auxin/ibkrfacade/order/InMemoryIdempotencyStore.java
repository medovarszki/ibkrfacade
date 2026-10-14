package hu.auxin.ibkrfacade.order;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryIdempotencyStore implements IdempotencyStore {

    final Map<String, OrderReservation> reservations = new ConcurrentHashMap<>();
    volatile boolean down;

    @Override
    public boolean reserve(OrderReservation reservation) {
        check();
        return reservations.putIfAbsent(reservation.clientOrderId(), reservation) == null;
    }

    @Override
    public Optional<OrderReservation> find(String clientOrderId) {
        check();
        return Optional.ofNullable(reservations.get(clientOrderId));
    }

    private void check() {
        if (down) {
            throw new IllegalStateException("Redis is down");
        }
    }
}
