package hu.auxin.ibkrfacade.marketdata;

import com.ib.client.Decimal;
import com.ib.client.TickType;
import hu.auxin.ibkrfacade.tws.Decimals;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Latest values of a market data subscription, kept in memory only (lost on a restart of the facade).
 * The delayed tick types are stored as their live counterparts.
 */
final class LiveQuote {

    private Double bid;
    private Double ask;
    private Double last;
    private Instant bidTime;
    private Instant askTime;
    private Instant lastTime;
    private BigDecimal bidSize;
    private BigDecimal askSize;
    private BigDecimal lastSize;
    private BigDecimal volume;
    private BigDecimal openInterest;
    private Double close;
    private Double open;
    private Double high;
    private Double low;

    /**
     * @return false if the tick type is not stored
     */
    synchronized boolean price(TickType type, double price, Instant now) {
        switch (type) {
            case BID, DELAYED_BID -> {
                bid = price;
                bidTime = now;
            }
            case ASK, DELAYED_ASK -> {
                ask = price;
                askTime = now;
            }
            case LAST, DELAYED_LAST -> {
                last = price;
                lastTime = now;
            }
            case CLOSE, DELAYED_CLOSE -> close = price;
            case OPEN, DELAYED_OPEN -> open = price;
            case HIGH, DELAYED_HIGH -> high = price;
            case LOW, DELAYED_LOW -> low = price;
            default -> {
                return false;
            }
        }
        return true;
    }

    synchronized boolean size(TickType type, Decimal size) {
        BigDecimal value = Decimals.toBigDecimal(size);
        switch (type) {
            case BID_SIZE, DELAYED_BID_SIZE -> bidSize = value;
            case ASK_SIZE, DELAYED_ASK_SIZE -> askSize = value;
            case LAST_SIZE, DELAYED_LAST_SIZE -> lastSize = value;
            case VOLUME, DELAYED_VOLUME -> volume = value;
            case OPEN_INTEREST, FUTURES_OPEN_INTEREST -> openInterest = value;
            default -> {
                return false;
            }
        }
        return true;
    }

    synchronized MarketStats stats() {
        return new MarketStats(bidSize, askSize, lastSize, volume, openInterest, close, open, high, low);
    }

    /**
     * Bid of a combo, where a negative price is valid: TWS sends -1 with a zero size when there is no bid.
     */
    synchronized Double comboBid() {
        return missing(bid, bidSize) ? null : bid;
    }

    synchronized Double comboAsk() {
        return missing(ask, askSize) ? null : ask;
    }

    synchronized Double last() {
        return last;
    }

    synchronized Instant bidTime() {
        return bidTime;
    }

    synchronized Instant askTime() {
        return askTime;
    }

    synchronized Instant lastTime() {
        return lastTime;
    }

    private static boolean missing(Double price, BigDecimal size) {
        return price == null || (price == -1 && (size == null || size.signum() == 0));
    }
}
