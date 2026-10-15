package hu.auxin.ibkrfacade.account;

import com.ib.client.EClientSocket;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsException;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import hu.auxin.ibkrfacade.tws.TwsTimeoutException;
import hu.auxin.ibkrfacade.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Account summary through a single, persistent reqAccountSummary subscription per TWS session. TWS sends the
 * values after the request and the changes every 3 minutes; the endpoint returns the latest received values.
 * <p>
 * TWS allows only two active account summary subscriptions, and IB Gateway 10.50 (server version 226) ignores the
 * protobuf encoded cancelAccountSummary, so a request-cancel cycle per query fails with error 322 from the third
 * query on. That's why the subscription is never cancelled; it ends with the connection.
 */
@Slf4j
@Service
public class AccountService {

    static final String TAGS = String.join(",", "NetLiquidation", "TotalCashValue", "AvailableFunds",
            "BuyingPower", "InitMarginReq", "MaintMarginReq", "ExcessLiquidity", "GrossPositionValue", "Cushion",
            "FullInitMarginReq", "FullMaintMarginReq", "SettledCash", "EquityWithLoanValue");

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;
    private final Duration timeout;

    private final Object lock = new Object();
    private volatile Subscription subscription;

    public AccountService(TwsConnection connection, TwsRequestRegistry requestRegistry,
                          @Value("${ibkr.tws.request-timeout:15s}") Duration timeout) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
        this.timeout = timeout;
    }

    /**
     * @param account account id, or null for the first managed account
     */
    public AccountSummary getSummary(String account) {
        Subscription current = await(subscribe(false));
        List<AccountValue> values = List.copyOf(current.values.values());
        String selected = account != null ? account
                : values.stream().map(AccountValue::account).findFirst()
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "TWS returned no account summary"));
        return toSummary(selected, values.stream().filter(value -> selected.equals(value.account())).toList(),
                current.updatedAt);
    }

    /**
     * A new TWS session, or TWS lost the subscriptions (1101): the account summary has to be requested again.
     */
    @EventListener
    public void onConnected(TwsConnectedEvent event) {
        try {
            subscribe(true);
        } catch (RuntimeException e) {
            log.warn("Could not subscribe to the account summary: {}", e.getMessage());
        }
    }

    // -- callbacks, on the TWS reader thread

    /**
     * @return false if the request id does not belong to the account summary subscription
     */
    public boolean onAccountSummary(int requestId, AccountValue value) {
        Subscription current = subscription;
        if (current == null || current.requestId != requestId) {
            return false;
        }
        current.values.put(value.account() + "|" + value.tag(), value);
        current.updatedAt = Instant.now();
        return true;
    }

    /**
     * @return false if the request id does not belong to the account summary subscription
     */
    public boolean onAccountSummaryEnd(int requestId) {
        Subscription current = subscription;
        if (current == null || current.requestId != requestId) {
            return false;
        }
        current.previous = null;
        current.initial.complete(null);
        return true;
    }

    /**
     * @return false if the request id does not belong to the account summary subscription
     */
    public boolean onError(int requestId, int code, String message) {
        synchronized (lock) {
            Subscription current = subscription;
            if (current == null || current.requestId != requestId) {
                return false;
            }
            if (current.previous != null) {
                // eg. 322 if TWS kept the previous subscription after 1101: it keeps sending the updates
                log.warn("Account summary request failed ({}: {}), keeping the previous subscription", code, message);
                subscription = current.previous;
            } else {
                subscription = null;
            }
            current.initial.completeExceptionally(new TwsException(code, message));
            return true;
        }
    }

    // --

    /**
     * Returns the subscription of the current TWS session, requests it if there is none.
     *
     * @param force request a new subscription even if the current session already has one
     */
    private Subscription subscribe(boolean force) {
        EClientSocket client = connection.client();
        synchronized (lock) {
            Subscription current = subscription;
            boolean usable = current != null && current.client == client && !current.initial.isCompletedExceptionally();
            if (usable && !force) {
                return current;
            }
            Subscription created = new Subscription(client, requestRegistry.nextId(), usable ? current : null);
            subscription = created;
            log.info("Subscribing to the account summary (requestId={})", created.requestId);
            client.reqAccountSummary(created.requestId, "All", TAGS);
            return created;
        }
    }

    /**
     * Waits for the initial values of the subscription.
     */
    private Subscription await(Subscription requested) {
        try {
            requested.initial.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return requested;
        } catch (TimeoutException e) {
            throw new TwsTimeoutException("No account summary from TWS in " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TwsTimeoutException("Interrupted while waiting for the account summary");
        } catch (ExecutionException e) {
            Subscription current = subscription;
            if (current != null && current != requested && current.initial.isDone()
                    && !current.initial.isCompletedExceptionally()) {
                return current;
            }
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    AccountSummary toSummary(String account, List<AccountValue> values, Instant updatedAt) {
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
                raw, Instant.now(), updatedAt);
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

    /**
     * An account summary subscription on a TWS socket.
     */
    private static final class Subscription {

        final EClientSocket client;
        final int requestId;
        /**
         * Values by account|tag
         */
        final Map<String, AccountValue> values = new ConcurrentHashMap<>();
        final CompletableFuture<Void> initial = new CompletableFuture<>();
        volatile Instant updatedAt;
        /**
         * The subscription replaced by this one on the same socket until the initial values arrive
         */
        volatile Subscription previous;

        Subscription(EClientSocket client, int requestId, Subscription previous) {
            this.client = client;
            this.requestId = requestId;
            this.previous = previous;
        }
    }
}
