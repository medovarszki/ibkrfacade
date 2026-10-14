package hu.auxin.ibkrfacade.tws;

/**
 * TWS did not answer a request within the configured time.
 */
public class TwsTimeoutException extends RuntimeException {

    public TwsTimeoutException(String message) {
        super(message);
    }
}
