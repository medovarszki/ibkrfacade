package hu.auxin.ibkrfacade.account;

import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Account summary through reqAccountSummary. TWS allows only two active account summary subscriptions, so the
 * requests are serialized and the subscription is cancelled right after the snapshot arrived.
 */
@Slf4j
@Service
public class AccountService {

    static final String TAGS = String.join(",", "NetLiquidation", "TotalCashValue", "AvailableFunds",
            "BuyingPower", "InitMarginReq", "MaintMarginReq", "ExcessLiquidity", "GrossPositionValue", "Cushion",
            "FullInitMarginReq", "FullMaintMarginReq", "SettledCash", "EquityWithLoanValue");

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;

    public AccountService(TwsConnection connection, TwsRequestRegistry requestRegistry) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
    }

    /**
     * @param account account id, or null for the first managed account
     */
    public synchronized AccountSummary getSummary(String account) {
        AtomicInteger requestId = new AtomicInteger(-1);
        List<AccountValue> values;
        try {
            values = requestRegistry.call(id -> {
                requestId.set(id);
                connection.client().reqAccountSummary(id, "All", TAGS);
            });
        } finally {
            if (requestId.get() >= 0 && connection.isConnected()) {
                connection.client().cancelAccountSummary(requestId.get());
            }
        }
        String selected = account != null ? account
                : values.stream().map(AccountValue::account).findFirst()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "TWS returned no account summary"));
        return toSummary(selected, values.stream().filter(value -> selected.equals(value.account())).toList());
    }

    AccountSummary toSummary(String account, List<AccountValue> values) {
        if (values.isEmpty()) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND", "No account summary for the requested account");
        }
        Map<String, String> raw = new LinkedHashMap<>();
        String currency = null;
        for (AccountValue value : values) {
            raw.put(value.tag(), value.value());
            if (currency == null && "NetLiquidation".equals(value.tag())) {
                currency = value.currency();
            }
        }
        return new AccountSummary(account, hu.auxin.ibkrfacade.tws.AccountMode.of(List.of(account)), currency,
                number(raw, "NetLiquidation"), number(raw, "TotalCashValue"), number(raw, "AvailableFunds"),
                number(raw, "BuyingPower"), number(raw, "InitMarginReq"), number(raw, "MaintMarginReq"),
                number(raw, "ExcessLiquidity"), number(raw, "GrossPositionValue"), number(raw, "Cushion"),
                raw, Instant.now());
    }

    private static Double number(Map<String, String> values, String tag) {
        String value = values.get(tag);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
