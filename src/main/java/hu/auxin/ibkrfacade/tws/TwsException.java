package hu.auxin.ibkrfacade.tws;

import lombok.Getter;

/**
 * Error reported by TWS / IB Gateway through the error() callback for a request.
 */
@Getter
public class TwsException extends RuntimeException {

    /**
     * TWS error code, see https://www.interactivebrokers.com/campus/ibkr-api-page/tws-api-error-codes/
     */
    private final int code;

    public TwsException(int code, String message) {
        super(message);
        this.code = code;
    }
}
