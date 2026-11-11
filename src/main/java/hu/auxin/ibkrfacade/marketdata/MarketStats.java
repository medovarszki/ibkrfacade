package hu.auxin.ibkrfacade.marketdata;

import java.math.BigDecimal;

/**
 * Latest sizes and daily statistics of an instrument from the market data stream. Every value is null until TWS
 * sends the corresponding tick.
 *
 * @param close previous close; the previous settlement price for futures
 */
public record MarketStats(BigDecimal bidSize, BigDecimal askSize, BigDecimal lastSize, BigDecimal volume,
                          BigDecimal openInterest, Double close, Double open, Double high, Double low) {

    public static final MarketStats EMPTY = new MarketStats(null, null, null, null, null, null, null, null, null);
}
