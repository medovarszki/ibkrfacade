package hu.auxin.ibkrfacade.tws;

/**
 * There is no usable connection to TWS / IB Gateway at the moment.
 */
public class TwsNotConnectedException extends RuntimeException {

    public TwsNotConnectedException(String message) {
        super(message);
    }
}
