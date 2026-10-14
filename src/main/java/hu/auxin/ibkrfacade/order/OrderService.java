package hu.auxin.ibkrfacade.order;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Order;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.marketdata.MarketDataService;
import hu.auxin.ibkrfacade.marketdata.MarketPrice;
import hu.auxin.ibkrfacade.service.ContractManagerService;
import hu.auxin.ibkrfacade.service.OrderManagerService;
import hu.auxin.ibkrfacade.tws.AccountMode;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.web.ApiException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Places and cancels orders with the safety checks:
 * <ul>
 *     <li>idempotency: one order per client_order_id, ever (reserved in Redis before sending)</li>
 *     <li>no order on a live account unless ibkr.allow-live=true</li>
 *     <li>no order without a recent quote of the instrument</li>
 *     <li>unknown conid is resolved through TWS, or rejected with 404</li>
 * </ul>
 */
@Slf4j
@Service
public class OrderService {

    private static final int LOCK_STRIPES = 64;

    private final TwsConnection connection;
    private final ContractManagerService contractManagerService;
    private final MarketDataService marketDataService;
    private final OrderManagerService orderManagerService;
    private final IdempotencyStore idempotencyStore;

    private final boolean allowLive;
    private final Duration maxQuoteAge;
    private final Duration quoteWait;
    private final Duration ackTimeout;

    private final Object[] locks = new Object[LOCK_STRIPES];

    public OrderService(TwsConnection connection, ContractManagerService contractManagerService,
                        MarketDataService marketDataService, OrderManagerService orderManagerService,
                        IdempotencyStore idempotencyStore,
                        @Value("${ibkr.allow-live:false}") boolean allowLive,
                        @Value("${ibkr.order.max-quote-age:15m}") Duration maxQuoteAge,
                        @Value("${ibkr.order.quote-wait:3s}") Duration quoteWait,
                        @Value("${ibkr.order.ack-timeout:3s}") Duration ackTimeout) {
        this.connection = connection;
        this.contractManagerService = contractManagerService;
        this.marketDataService = marketDataService;
        this.orderManagerService = orderManagerService;
        this.idempotencyStore = idempotencyStore;
        this.allowLive = allowLive;
        this.maxQuoteAge = maxQuoteAge;
        this.quoteWait = quoteWait;
        this.ackTimeout = ackTimeout;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    public OrderResult place(PlaceOrderRequest request) {
        validate(request);
        String clientOrderId = request.clientOrderId();

        Optional<OrderResponse> existing = findExisting(request);
        if (existing.isPresent()) {
            return new OrderResult(existing.get(), false);
        }

        synchronized (locks[Math.floorMod(clientOrderId.hashCode(), LOCK_STRIPES)]) {
            existing = findExisting(request);
            if (existing.isPresent()) {
                return new OrderResult(existing.get(), false);
            }

            checkTradingAllowed();
            checkAccount(request.account());
            ContractHolder contractHolder = contractManagerService.getContractHolder(request.conid());
            Contract contract = contractHolder.getContract();
            checkQuote(contract);

            Order order = buildOrder(request);
            int orderId = connection.allocateOrderId();
            OrderReservation reservation = new OrderReservation(clientOrderId, orderId, request.fingerprint(),
                    Instant.now());
            if (!reserve(reservation)) {
                // reserved concurrently, eg. by another instance of the facade
                return new OrderResult(findExisting(request).orElseThrow(), false);
            }

            OrderRecord record = orderManagerService.register(
                    OrderRecord.placed(orderId, connection.getClientId(), clientOrderId, contract, order));
            log.info("Placing order {} (client_order_id={}): {} {} {} @ {} {}", orderId, clientOrderId,
                    request.action(), request.quantity(), contract.symbol(), request.effectiveOrderType(),
                    request.limitPrice() == null ? "" : request.limitPrice());
            long version = record.getVersion();
            try {
                orderManagerService.transmit(record, contract, order);
            } catch (RuntimeException e) {
                log.error("Sending order {} to TWS failed", orderId, e);
                record.applyError(-1, "Sending the order to TWS failed: " + e.getMessage(), false);
                return new OrderResult(record.toResponse(false), true);
            }
            awaitUpdate(record, version);
            return new OrderResult(record.toResponse(false), true);
        }
    }

    /**
     * Requests the cancellation of an active order.
     */
    public OrderResponse cancel(int orderId) {
        OrderRecord record = orderManagerService.findByOrderId(orderId)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "No order found with order id " + orderId));
        if (!record.isActive()) {
            throw ApiException.conflict("ORDER_NOT_ACTIVE",
                    "Order " + orderId + " is not active (status: " + record.getStatus() + ")");
        }
        log.info("Cancelling order {} (client_order_id={})", orderId, record.getClientOrderId());
        long version = record.getVersion();
        orderManagerService.cancel(orderId);
        awaitUpdate(record, version);
        return record.toResponse(false);
    }

    public OrderResponse getByOrderId(int orderId) {
        return orderManagerService.findByOrderId(orderId)
                .map(record -> record.toResponse(false))
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "No order found with order id " + orderId));
    }

    public OrderResponse getByClientOrderId(String clientOrderId) {
        Optional<OrderRecord> record = orderManagerService.findByClientOrderId(clientOrderId);
        if (record.isPresent()) {
            return record.get().toResponse(false);
        }
        return idempotencyStore.find(clientOrderId)
                .map(this::fromReservation)
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND",
                        "No order found with client_order_id " + clientOrderId));
    }

    public List<OrderResponse> getAll(boolean activeOnly) {
        List<OrderRecord> records = activeOnly ? orderManagerService.getActiveOrders() : orderManagerService.getAllOrders();
        return records.stream().map(record -> record.toResponse(false)).toList();
    }

    // -- internals

    private Optional<OrderResponse> findExisting(PlaceOrderRequest request) {
        Optional<OrderReservation> reservation = findReservation(request.clientOrderId());
        if (reservation.isPresent() && StringUtils.hasText(reservation.get().fingerprint())
                && !reservation.get().fingerprint().equals(request.fingerprint())) {
            throw ApiException.conflict("CLIENT_ORDER_ID_REUSED", "client_order_id " + request.clientOrderId()
                    + " was already used for an order with different parameters");
        }
        Optional<OrderRecord> record = orderManagerService.findByClientOrderId(request.clientOrderId());
        if (record.isPresent()) {
            return Optional.of(record.get().toResponse(true));
        }
        return reservation.map(this::fromReservation).map(response -> withDuplicate(response));
    }

    /**
     * The client order id is reserved, but the order is not in the memory. It is looked up by the order id,
     * or reported with Unknown status. It is never sent again.
     */
    private OrderResponse fromReservation(OrderReservation reservation) {
        return orderManagerService.findByOrderId(reservation.orderId())
                .map(record -> record.toResponse(false))
                .orElseGet(() -> OrderRecord.unknown(reservation.orderId(), reservation.clientOrderId(),
                        reservation.reservedAt()).toResponse(false));
    }

    private static OrderResponse withDuplicate(OrderResponse r) {
        return new OrderResponse(r.orderId(), r.permId(), r.clientOrderId(), r.account(), r.conid(), r.symbol(),
                r.secType(), r.currency(), r.action(), r.orderType(), r.quantity(), r.limitPrice(), r.stopPrice(),
                r.tif(), r.outsideRth(), r.status(), r.active(), r.filledQuantity(), r.remainingQuantity(),
                r.avgFillPrice(), r.commission(), r.commissionCurrency(), r.realizedPnl(), r.executions(),
                r.errorCode(), r.errorMessage(), r.warnings(), r.createdAt(), r.updatedAt(), true);
    }

    private Optional<OrderReservation> findReservation(String clientOrderId) {
        try {
            return idempotencyStore.find(clientOrderId);
        } catch (RuntimeException e) {
            throw storeUnavailable(e);
        }
    }

    private boolean reserve(OrderReservation reservation) {
        try {
            return idempotencyStore.reserve(reservation);
        } catch (RuntimeException e) {
            throw storeUnavailable(e);
        }
    }

    private static ApiException storeUnavailable(RuntimeException e) {
        log.error("Idempotency store is not available", e);
        return ApiException.unavailable("IDEMPOTENCY_STORE_UNAVAILABLE",
                "The idempotency store (Redis) is not available, no order is placed: " + e.getMessage());
    }

    private void validate(PlaceOrderRequest request) {
        OrderKind kind = request.effectiveOrderType();
        if (kind.needsLimitPrice() && request.limitPrice() == null) {
            throw ApiException.badRequest("LIMIT_PRICE_REQUIRED", "limit_price is required for " + kind + " orders");
        }
        if (!kind.needsLimitPrice() && request.limitPrice() != null) {
            throw ApiException.badRequest("UNEXPECTED_LIMIT_PRICE", "limit_price is not allowed for " + kind + " orders");
        }
        if (kind.needsStopPrice() && request.stopPrice() == null) {
            throw ApiException.badRequest("STOP_PRICE_REQUIRED", "stop_price is required for " + kind + " orders");
        }
        if (!kind.needsStopPrice() && request.stopPrice() != null) {
            throw ApiException.badRequest("UNEXPECTED_STOP_PRICE", "stop_price is not allowed for " + kind + " orders");
        }
    }

    private void checkTradingAllowed() {
        if (!connection.isReady()) {
            throw ApiException.unavailable("TWS_NOT_CONNECTED", "Not connected to TWS / IB Gateway, no order is placed");
        }
        if (!connection.hasFarmConnectivity()) {
            throw ApiException.unavailable("TWS_NO_CONNECTIVITY",
                    "TWS / IB Gateway has no connection to the IB servers, no order is placed");
        }
        AccountMode mode = connection.getAccountMode();
        if (mode == AccountMode.UNKNOWN) {
            throw ApiException.unavailable("ACCOUNT_MODE_UNKNOWN",
                    "The type of the connected account (paper/live) is not known yet, no order is placed");
        }
        if (mode == AccountMode.LIVE && !allowLive) {
            throw ApiException.forbidden("LIVE_TRADING_DISABLED",
                    "Connected to a live account and live trading is disabled (IBKR_ALLOW_LIVE=false)");
        }
    }

    private void checkAccount(String account) {
        if (account != null && !connection.getAccounts().contains(account)) {
            throw ApiException.badRequest("UNKNOWN_ACCOUNT", "The account is not managed by the connected gateway");
        }
    }

    private void checkQuote(Contract contract) {
        MarketPrice price = marketDataService.awaitQuote(contract, maxQuoteAge, quoteWait);
        if (!price.hasQuote(maxQuoteAge, Instant.now())) {
            StringBuilder detail = new StringBuilder("No market price (bid/ask) for conid ").append(contract.conid());
            if (price.quoteTime() != null) {
                detail.append(", the latest quote is from ").append(price.quoteTime());
            }
            if (price.streamError() != null) {
                detail.append(", market data error ").append(price.streamError());
            }
            throw ApiException.unprocessable("MARKET_DATA_UNAVAILABLE", detail.append(", no order is placed").toString());
        }
    }

    private Order buildOrder(PlaceOrderRequest request) {
        Order order = new Order();
        order.action(request.action() == OrderSide.BUY ? Types.Action.BUY : Types.Action.SELL);
        order.orderType(request.effectiveOrderType().toOrderType());
        order.totalQuantity(Decimal.get(request.quantity()));
        if (request.limitPrice() != null) {
            order.lmtPrice(request.limitPrice());
        }
        if (request.stopPrice() != null) {
            order.auxPrice(request.stopPrice());
        }
        order.tif(request.effectiveTif().name());
        order.outsideRth(request.effectiveOutsideRth());
        order.orderRef(request.clientOrderId());
        if (request.account() != null) {
            order.account(request.account());
        }
        order.transmit(true);
        return order;
    }

    private void awaitUpdate(OrderRecord record, long sinceVersion) {
        try {
            if (!record.awaitUpdate(sinceVersion, ackTimeout)) {
                log.info("No update from TWS for order {} in {}ms", record.getOrderId(), ackTimeout.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
