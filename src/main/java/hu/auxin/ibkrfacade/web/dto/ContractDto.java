package hu.auxin.ibkrfacade.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.tws.Decimals;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Instrument descriptor. The conid is the unique IBKR contract id.")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContractDto(
        int conid,
        String symbol,
        @JsonProperty("sec_type") String secType,
        String exchange,
        @JsonProperty("primary_exchange") String primaryExchange,
        String currency,
        @JsonProperty("local_symbol") String localSymbol,
        @JsonProperty("trading_class") String tradingClass,
        String multiplier,
        @JsonProperty("last_trade_date") String lastTradeDate,
        Double strike,
        String right,
        String description,
        @JsonProperty("long_name") String longName,
        @Schema(description = "Time zone of the exchange, eg. US/Eastern") @JsonProperty("time_zone") String timeZone,
        @JsonProperty("min_tick") Double minTick,
        @JsonProperty("min_size") BigDecimal minSize,
        @JsonProperty("size_increment") BigDecimal sizeIncrement,
        String industry,
        String category,
        @JsonProperty("stock_type") String stockType,
        @JsonProperty("valid_exchanges") String validExchanges,
        @JsonProperty("trading_hours") String tradingHours,
        @JsonProperty("liquid_hours") String liquidHours,
        @Schema(description = "Ticker id of the market data subscription in the current session, if subscribed")
        @JsonProperty("stream_request_id") Integer streamRequestId) {

    public static ContractDto of(Contract contract) {
        return of(contract, null, null);
    }

    public static ContractDto of(ContractHolder holder) {
        return of(holder.getContract(), holder.getDetails(), holder.getStreamRequestId());
    }

    public static ContractDto of(Contract c, ContractDetails d, Integer streamRequestId) {
        return new ContractDto(c.conid(), c.symbol(), c.getSecType(), empty(c.exchange()), empty(c.primaryExch()),
                c.currency(), empty(c.localSymbol()), empty(c.tradingClass()), empty(c.multiplier()),
                empty(c.lastTradeDateOrContractMonth()), price(c.strike()), empty(c.getRight()),
                empty(c.description()),
                d == null ? null : empty(d.longName()),
                d == null ? null : empty(d.timeZoneId()),
                d == null ? null : price(d.minTick()),
                d == null ? null : Decimals.toBigDecimal(d.minSize()),
                d == null ? null : Decimals.toBigDecimal(d.sizeIncrement()),
                d == null ? null : empty(d.industry()),
                d == null ? null : empty(d.category()),
                d == null ? null : empty(d.stockType()),
                d == null ? null : empty(d.validExchanges()),
                d == null ? null : empty(d.tradingHours()),
                d == null ? null : empty(d.liquidHours()),
                streamRequestId);
    }

    /**
     * TWS sends 0 or Double.MAX_VALUE when the value is not set (eg. the strike of a stock)
     */
    private static Double price(double value) {
        return value == 0 ? null : Decimals.orNull(value);
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() || "?".equals(value) || "0".equals(value) ? null : value;
    }
}
