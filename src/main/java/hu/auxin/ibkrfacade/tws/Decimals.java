package hu.auxin.ibkrfacade.tws;

import com.ib.client.Decimal;

import java.math.BigDecimal;

/**
 * Conversions for the TWS API Decimal type.
 */
public final class Decimals {

    private Decimals() {
    }

    /**
     * @return null for null, invalid or NaN values
     */
    public static BigDecimal toBigDecimal(Decimal decimal) {
        if (decimal == null || !decimal.isValid()) {
            return null;
        }
        return decimal.value().stripTrailingZeros();
    }

    public static double toDouble(Decimal decimal) {
        BigDecimal value = toBigDecimal(decimal);
        return value == null ? 0 : value.doubleValue();
    }

    /**
     * TWS uses Double.MAX_VALUE for "not set" double values.
     *
     * @return null if the value is not set
     */
    public static Double orNull(double value) {
        return value == Double.MAX_VALUE || Double.isNaN(value) ? null : value;
    }
}
