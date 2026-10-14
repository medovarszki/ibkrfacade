package hu.auxin.ibkrfacade.tws;

import java.util.Set;

/**
 * Classification of the TWS error codes the facade handles explicitly.
 *
 * @see <a href="https://www.interactivebrokers.com/campus/ibkr-api-page/tws-api-error-codes/">TWS API error codes</a>
 */
public final class TwsErrorCodes {

    public static final int NO_SECURITY_DEFINITION = 200;
    public static final int ORDER_REJECTED = 201;
    public static final int ORDER_CANCELLED = 202;
    public static final int ORDER_MESSAGE = 399;
    public static final int ALREADY_CONNECTED = 501;
    public static final int CONNECT_FAIL = 502;
    public static final int NOT_CONNECTED = 504;
    public static final int BAD_MESSAGE = 507;
    public static final int CONNECTIVITY_LOST = 1100;
    public static final int CONNECTIVITY_RESTORED_DATA_LOST = 1101;
    public static final int CONNECTIVITY_RESTORED_DATA_MAINTAINED = 1102;
    public static final int MARKET_DATA_NOT_SUBSCRIBED = 354;
    public static final int DELAYED_MARKET_DATA = 10167;

    private static final Set<Integer> CONNECTION_ERRORS = Set.of(CONNECT_FAIL, NOT_CONNECTED, BAD_MESSAGE);

    private TwsErrorCodes() {
    }

    /**
     * Informational messages and warnings which must not fail the request they belong to
     * (eg. "Market data farm connection is OK", "Displaying delayed market data", order warnings).
     */
    public static boolean isWarning(int code) {
        return (code >= 2100 && code < 2200) || code == DELAYED_MARKET_DATA || code == ORDER_MESSAGE;
    }

    public static boolean isConnectionError(int code) {
        return CONNECTION_ERRORS.contains(code);
    }
}
