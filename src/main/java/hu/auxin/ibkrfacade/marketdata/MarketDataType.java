package hu.auxin.ibkrfacade.marketdata;

/**
 * Type of the market data sent by TWS for a subscription (marketDataType callback). A paper account without live
 * market data subscriptions gets DELAYED data; FROZEN is the last value at the close of the market.
 */
public enum MarketDataType {

    LIVE, FROZEN, DELAYED, DELAYED_FROZEN;

    /**
     * @param code 1 = live, 2 = frozen, 3 = delayed, 4 = delayed frozen
     * @return null for an unknown code
     */
    public static MarketDataType of(int code) {
        return code >= 1 && code <= values().length ? values()[code - 1] : null;
    }
}
