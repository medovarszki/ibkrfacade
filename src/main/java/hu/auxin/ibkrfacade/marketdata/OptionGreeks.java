package hu.auxin.ibkrfacade.marketdata;

/**
 * Option model values computed by IBKR (tickOptionComputation, model tick). Every value is null until TWS sends it,
 * or when TWS could not compute it.
 *
 * @param impliedVol implied volatility, eg. 0.82 for 82%
 */
public record OptionGreeks(Double impliedVol, Double delta, Double gamma, Double vega, Double theta) {

    public static final OptionGreeks EMPTY = new OptionGreeks(null, null, null, null, null);

    /**
     * Converts the values of tickOptionComputation: the API decoder sends Double.MAX_VALUE for a value which is not
     * computed yet (TWS itself sends -1 for the implied volatility and -2 for the delta).
     */
    public static OptionGreeks of(double impliedVol, double delta, double gamma, double vega, double theta) {
        return new OptionGreeks(impliedVol < 0 ? null : value(impliedVol), delta == -2 ? null : value(delta),
                value(gamma), value(vega), value(theta));
    }

    private static Double value(double value) {
        return Double.isNaN(value) || Double.isInfinite(value) || Math.abs(value) == Double.MAX_VALUE ? null : value;
    }
}
