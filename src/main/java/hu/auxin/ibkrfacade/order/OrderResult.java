package hu.auxin.ibkrfacade.order;

/**
 * @param created true if a new order was placed, false if an existing order was returned for the client order id
 */
public record OrderResult(OrderResponse order, boolean created) {
}
