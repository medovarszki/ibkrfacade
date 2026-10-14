package hu.auxin.ibkrfacade.service;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.EClientSocket;
import com.ib.client.Execution;
import com.ib.client.ExecutionFilter;
import com.ib.client.Order;
import com.ib.client.OrderState;
import hu.auxin.ibkrfacade.order.OrderRecord;
import hu.auxin.ibkrfacade.tws.TwsConnectedEvent;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import hu.auxin.ibkrfacade.tws.TwsRequestRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of the orders: the orders placed through the facade and the ones reported by TWS
 * (open orders, completed orders and executions of the day). State is merged from the TWS callbacks.
 */
@Slf4j
@Service
public class OrderManagerService {

    private final TwsConnection connection;
    private final TwsRequestRegistry requestRegistry;

    private final Map<Integer, OrderRecord> byOrderId = new ConcurrentHashMap<>();
    private final Map<Long, OrderRecord> byPermId = new ConcurrentHashMap<>();
    private final Map<String, OrderRecord> byClientOrderId = new ConcurrentHashMap<>();
    private final Map<String, OrderRecord> byExecId = new ConcurrentHashMap<>();

    public OrderManagerService(TwsConnection connection, TwsRequestRegistry requestRegistry) {
        this.connection = connection;
        this.requestRegistry = requestRegistry;
    }

    /**
     * Registers an order before it's sent to TWS, so the callbacks will update this record.
     *
     * @return the registered record, or the already known one with the same client order id
     */
    public synchronized OrderRecord register(OrderRecord record) {
        if (record.getClientOrderId() != null) {
            OrderRecord existing = byClientOrderId.get(record.getClientOrderId());
            if (existing != null) {
                return existing;
            }
        }
        index(record);
        return record;
    }

    /**
     * Sends a registered order to TWS. The caller is responsible for the safety checks.
     */
    public void transmit(OrderRecord record, Contract contract, Order order) {
        connection.client().placeOrder(record.getOrderId(), contract, order);
    }

    public void cancel(int orderId) {
        connection.client().cancelOrder(orderId, new com.ib.client.OrderCancel());
    }

    // -- queries

    public Optional<OrderRecord> findByOrderId(int orderId) {
        return Optional.ofNullable(byOrderId.get(orderId));
    }

    public Optional<OrderRecord> findByClientOrderId(String clientOrderId) {
        return Optional.ofNullable(byClientOrderId.get(clientOrderId));
    }

    /**
     * All known orders, the most recent first.
     */
    public List<OrderRecord> getAllOrders() {
        Collection<OrderRecord> all = new LinkedHashSet<>();
        all.addAll(byOrderId.values());
        all.addAll(byPermId.values());
        all.addAll(byClientOrderId.values());
        List<OrderRecord> result = new ArrayList<>(all);
        result.sort(Comparator.comparing((OrderRecord record) -> record.toResponse(false).createdAt()).reversed());
        return result;
    }

    public List<OrderRecord> getActiveOrders() {
        return getAllOrders().stream().filter(OrderRecord::isActive).toList();
    }

    public List<OrderRecord> getActiveOrdersByConid(int conid) {
        return getActiveOrders().stream().filter(record -> record.getConid() != null && record.getConid() == conid)
                .toList();
    }

    // -- reconciliation

    /**
     * After every (re)connection: request the open orders of every client, the completed orders and the
     * executions of the day, so the state survives restarts of the facade and of IB Gateway.
     */
    @EventListener
    public void onConnected(TwsConnectedEvent event) {
        EClientSocket client = connection.client();
        client.reqAllOpenOrders();
        client.reqCompletedOrders(false);
        client.reqExecutions(requestRegistry.nextId(), new ExecutionFilter());
    }

    // -- callbacks

    public synchronized void onOpenOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        OrderRecord record = resolve(orderId, order.clientId(), order.permId(), order.orderRef());
        record.applyOpenOrder(orderId, contract, order, orderState);
        index(record);
    }

    public synchronized void onOrderStatus(int orderId, long permId, String status, Decimal filled, Decimal remaining,
                                           double avgFillPrice) {
        OrderRecord record = find(orderId, connection.getClientId(), permId, null);
        if (record == null) {
            log.debug("Order status for unknown order {} (permId={}): {}", orderId, permId, status);
            return;
        }
        record.applyStatus(permId, status, filled, remaining, avgFillPrice);
        index(record);
    }

    public synchronized void onCompletedOrder(Contract contract, Order order, OrderState orderState) {
        OrderRecord record = resolve(order.orderId(), order.clientId(), order.permId(), order.orderRef());
        record.applyCompletedOrder(contract, order, orderState);
        index(record);
    }

    public synchronized void onExecution(Contract contract, Execution execution) {
        OrderRecord record = resolve(execution.orderId(), execution.clientId(), execution.permId(),
                execution.orderRef());
        record.applyExecution(contract, execution);
        byExecId.put(execution.execId(), record);
        index(record);
    }

    public void onCommissionReport(CommissionAndFeesReport report) {
        OrderRecord record = byExecId.get(report.execId());
        if (record != null) {
            record.applyCommission(report);
        }
    }

    /**
     * @return false if the order id is not known
     */
    public boolean onOrderError(int orderId, int code, String message, boolean warning) {
        OrderRecord record = byOrderId.get(orderId);
        if (record == null) {
            return false;
        }
        if (warning) {
            log.info("Order {} message {}: {}", orderId, code, message);
        } else {
            log.warn("Order {} error {}: {}", orderId, code, message);
        }
        record.applyError(code, message, warning);
        return true;
    }

    private OrderRecord resolve(int orderId, int clientId, long permId, String orderRef) {
        OrderRecord record = find(orderId, clientId, permId, orderRef);
        return record != null ? record : new OrderRecord(java.time.Instant.now());
    }

    private OrderRecord find(int orderId, int clientId, long permId, String orderRef) {
        OrderRecord record = null;
        if (orderId > 0 && clientId == connection.getClientId()) {
            record = byOrderId.get(orderId);
        }
        if (record == null && permId > 0) {
            record = byPermId.get(permId);
        }
        if (record == null && StringUtils.hasText(orderRef)) {
            record = byClientOrderId.get(orderRef);
        }
        return record;
    }

    private void index(OrderRecord record) {
        if (record.getOrderId() > 0 && record.getClientId() == connection.getClientId()) {
            byOrderId.putIfAbsent(record.getOrderId(), record);
        }
        if (record.getPermId() > 0) {
            byPermId.putIfAbsent(record.getPermId(), record);
        }
        if (StringUtils.hasText(record.getClientOrderId())) {
            byClientOrderId.putIfAbsent(record.getClientOrderId(), record);
        }
    }
}
