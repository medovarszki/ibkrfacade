package hu.auxin.ibkrfacade.tws;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.EWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the socket connection to TWS / IB Gateway: connects at startup without blocking it, reconnects when the
 * connection is lost (eg. at the daily restart of IB Gateway) and hands out the order ids in a thread safe way.
 */
@Slf4j
@Component
public class TwsConnection implements SmartLifecycle {

    private final String host;
    private final int port;
    private final int clientId;
    private final Duration reconnectInterval;

    private final ObjectProvider<EWrapper> wrapperProvider;
    private final ApplicationEventPublisher eventPublisher;
    private final TwsRequestRegistry requestRegistry;

    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(
            runnable -> daemon(runnable, "tws-connect"));
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor(
            runnable -> daemon(runnable, "tws-events"));

    private volatile EClientSocket client;
    private volatile boolean ready;
    private volatile boolean farmConnectivity = true;
    private volatile List<String> accounts = List.of();
    private volatile Instant connectedSince;
    private volatile String lastError;
    private volatile ScheduledFuture<?> reconnectTask;

    /**
     * Next order id to use. Negative until the first nextValidId() callback arrives.
     */
    private final AtomicInteger nextOrderId = new AtomicInteger(-1);

    /**
     * Number of API sessions (successful connections) since the start of the application.
     */
    private final AtomicInteger sessions = new AtomicInteger();

    public TwsConnection(@Value("${ibkr.tws.host}") String host,
                         @Value("${ibkr.tws.port}") int port,
                         @Value("${ibkr.tws.client-id:1}") int clientId,
                         @Value("${ibkr.tws.reconnect-interval:10s}") Duration reconnectInterval,
                         ObjectProvider<EWrapper> wrapperProvider,
                         ApplicationEventPublisher eventPublisher,
                         TwsRequestRegistry requestRegistry) {
        this.host = host;
        this.port = port;
        this.clientId = clientId;
        this.reconnectInterval = reconnectInterval;
        this.wrapperProvider = wrapperProvider;
        this.eventPublisher = eventPublisher;
        this.requestRegistry = requestRegistry;
    }

    // -- lifecycle

    @Override
    public void start() {
        reconnectTask = reconnectExecutor.scheduleWithFixedDelay(this::ensureConnected, 0,
                reconnectInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
        }
        EClientSocket current = client;
        if (current != null && current.isConnected()) {
            current.eDisconnect();
        }
        reconnectExecutor.shutdownNow();
        eventExecutor.shutdownNow();
    }

    @Override
    public boolean isRunning() {
        return reconnectTask != null && !reconnectTask.isCancelled();
    }

    synchronized void ensureConnected() {
        EClientSocket current = client;
        if (current != null && current.isConnected()) {
            return;
        }
        ready = false;
        try {
            EReaderSignal signal = new EJavaSignal();
            EClientSocket socket = new EClientSocket(wrapperProvider.getObject(), signal);
            socket.eConnect(host, port, clientId);
            if (!socket.isConnected()) {
                log.warn("TWS / IB Gateway is not reachable at {}:{}, retrying in {}s", host, port,
                        reconnectInterval.toSeconds());
                return;
            }
            attach(socket);
            EReader reader = new EReader(socket, signal);
            reader.start();
            daemon(() -> {
                while (socket.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        log.error("Error while processing TWS messages", e);
                    }
                }
            }, "tws-reader-" + clientId).start();
            log.info("Connected to TWS / IB Gateway at {}:{} (clientId={}, serverVersion={})", host, port, clientId,
                    socket.serverVersion());
        } catch (Exception e) {
            log.warn("Connecting to TWS / IB Gateway at {}:{} failed: {}", host, port, e.getMessage());
        }
    }

    /**
     * Starts a new API session on the given (connected) socket.
     */
    void attach(EClientSocket socket) {
        ready = false;
        client = socket;
        connectedSince = Instant.now();
    }

    // -- callbacks from the EWrapper

    void onNextValidId(int orderId) {
        int next = nextOrderId.accumulateAndGet(orderId, Math::max);
        log.debug("nextValidId={}, using {}", orderId, next);
        if (!ready) {
            ready = true;
            publish(new TwsConnectedEvent(sessions.getAndIncrement() > 0));
        }
    }

    void onManagedAccounts(String accountsList) {
        List<String> list = Arrays.stream(accountsList.split(","))
                .map(String::trim)
                .filter(account -> !account.isEmpty())
                .toList();
        this.accounts = list;
        AccountMode mode = AccountMode.of(list);
        log.info("Connected to {} account(s): {} -> {} TRADING", list.size(),
                list.stream().map(TwsConnection::maskAccount).toList(), mode);
        if (mode == AccountMode.LIVE) {
            log.warn("The connected account is a LIVE account!");
        }
    }

    void onConnectivityLost() {
        farmConnectivity = false;
    }

    void onConnectivityRestored(boolean dataLost) {
        farmConnectivity = true;
        if (dataLost) {
            publish(new TwsConnectedEvent(true));
        }
    }

    void onConnectionClosed() {
        ready = false;
        log.warn("Connection to TWS / IB Gateway closed, reconnecting in {}s", reconnectInterval.toSeconds());
        requestRegistry.failAll(new TwsNotConnectedException("Connection to TWS closed"));
    }

    void onError(String message) {
        lastError = message;
    }

    private void publish(Object event) {
        eventExecutor.execute(() -> {
            try {
                eventPublisher.publishEvent(event);
            } catch (Exception e) {
                log.error("Error while handling {}", event, e);
            }
        });
    }

    // -- public API

    /**
     * The socket client for sending requests to TWS.
     *
     * @throws TwsNotConnectedException if there is no connection
     */
    public EClientSocket client() {
        EClientSocket current = client;
        if (current == null || !current.isConnected()) {
            throw new TwsNotConnectedException("Not connected to TWS / IB Gateway at " + host + ":" + port);
        }
        return current;
    }

    public boolean isConnected() {
        EClientSocket current = client;
        return current != null && current.isConnected();
    }

    /**
     * Connected, and TWS already sent the next valid order id, so the API is usable.
     */
    public boolean isReady() {
        return isConnected() && ready;
    }

    /**
     * False while TWS reports that its connection to the IB servers is broken (1100).
     */
    public boolean hasFarmConnectivity() {
        return farmConnectivity;
    }

    /**
     * Returns a unique order id. Thread safe; the sequence starts from the nextValidId sent by TWS.
     *
     * @throws TwsNotConnectedException if the connection is not ready yet
     */
    public int allocateOrderId() {
        if (!isReady()) {
            throw new TwsNotConnectedException("TWS connection is not ready, no valid order id yet");
        }
        int orderId = nextOrderId.getAndIncrement();
        if (TwsRequestRegistry.isRequestId(orderId)) {
            throw new IllegalStateException("Order id " + orderId + " reached the request id range");
        }
        return orderId;
    }

    public List<String> getAccounts() {
        return accounts;
    }

    public AccountMode getAccountMode() {
        return AccountMode.of(accounts);
    }

    public Instant getConnectedSince() {
        return connectedSince;
    }

    public String getLastError() {
        return lastError;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getClientId() {
        return clientId;
    }

    /**
     * Masks an account id for logging, eg. DU1234567 -> DU****567
     */
    public static String maskAccount(String account) {
        if (account == null || account.length() <= 5) {
            return "****";
        }
        return account.substring(0, 2) + "****" + account.substring(account.length() - 3);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
