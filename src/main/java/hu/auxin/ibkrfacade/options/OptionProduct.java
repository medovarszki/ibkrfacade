package hu.auxin.ibkrfacade.options;

import com.ib.client.Types;

/**
 * Options which expire together with a VIX future (VX).
 */
public enum OptionProduct {

    /**
     * Monthly VIX index options (Cboe, trading class VIX; the weeklies are VIXW). They settle to the same SOQ as the
     * VX future of the same expiration, but their last trading day is the day before (eg. 20261117 for VXX6
     * expiring on 20261118).
     */
    VIX(Types.SecType.OPT, "SMART", "VIX"),

    /**
     * Options on the VX future itself, listed on CFE. Only usable if IBKR offers such contracts.
     */
    VX_FOP(Types.SecType.FOP, "CFE", null);

    private final Types.SecType secType;
    private final String exchange;
    private final String tradingClass;

    OptionProduct(Types.SecType secType, String exchange, String tradingClass) {
        this.secType = secType;
        this.exchange = exchange;
        this.tradingClass = tradingClass;
    }

    public Types.SecType secType() {
        return secType;
    }

    public String exchange() {
        return exchange;
    }

    /**
     * @return null if any trading class is accepted
     */
    public String tradingClass() {
        return tradingClass;
    }

    /**
     * Options on the future are filtered by their underlying conid, the index options by their expiration only.
     */
    boolean onFuture() {
        return secType == Types.SecType.FOP;
    }
}
