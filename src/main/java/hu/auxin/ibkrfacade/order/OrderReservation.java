package hu.auxin.ibkrfacade.order;

import java.time.Instant;

/**
 * Persistent record of a client order id, written before the order is sent to TWS.
 *
 * @param fingerprint canonical form of the order parameters
 */
public record OrderReservation(String clientOrderId, int orderId, String fingerprint, Instant reservedAt) {
}
