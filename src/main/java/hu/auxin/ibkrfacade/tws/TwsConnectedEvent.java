package hu.auxin.ibkrfacade.tws;

/**
 * Published when a (re)connection to TWS is established and the API is ready to use (nextValidId arrived),
 * or when TWS reports that its connectivity was restored but the subscriptions were lost (1101).
 * Listeners are invoked on a dedicated thread, never on the TWS reader thread.
 */
public record TwsConnectedEvent(boolean reconnect) {
}
