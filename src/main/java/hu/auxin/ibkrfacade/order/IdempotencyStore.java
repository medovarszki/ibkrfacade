package hu.auxin.ibkrfacade.order;

import java.util.Optional;

/**
 * Persistent client_order_id -> order id mapping. The reservation is written atomically before the order is sent,
 * so a client order id can't be used twice even in case of concurrent requests or a restart of the facade.
 */
public interface IdempotencyStore {

    /**
     * Atomically stores the reservation if there's no reservation for the client order id yet.
     *
     * @return false if the client order id is already reserved
     */
    boolean reserve(OrderReservation reservation);

    Optional<OrderReservation> find(String clientOrderId);
}
