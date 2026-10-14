package hu.auxin.ibkrfacade.order;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.OrderStatus;
import hu.auxin.ibkrfacade.tws.Decimals;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Mutable, thread safe state of an order, merged from the openOrder, orderStatus, completedOrder, execDetails,
 * commissionAndFeesReport and error callbacks.
 */
public class OrderRecord {

    public static final String STATUS_REJECTED = "Rejected";
    public static final String STATUS_UNKNOWN = "Unknown";

    private static final Set<String> FINAL_STATUSES = Set.of(OrderStatus.Cancelled.name(),
            OrderStatus.ApiCancelled.name(), OrderStatus.Filled.name(), OrderStatus.Inactive.name(), STATUS_REJECTED);
    private static final Set<String> ACTIVE_STATUSES = Set.of(OrderStatus.PreSubmitted.name(),
            OrderStatus.PendingCancel.name(), OrderStatus.Submitted.name(), OrderStatus.PendingSubmit.name(),
            OrderStatus.ApiPending.name());

    private String clientOrderId;
    private int orderId;
    private long permId;
    private int clientId;
    private String account;
    private Integer conid;
    private String symbol;
    private String secType;
    private String currency;
    private String action;
    private String orderType;
    private BigDecimal quantity;
    private Double limitPrice;
    private Double stopPrice;
    private String tif;
    private boolean outsideRth;

    private String status = OrderStatus.PendingSubmit.name();
    private boolean acknowledged;
    private BigDecimal filled = BigDecimal.ZERO;
    private BigDecimal remaining;
    private Double avgFillPrice;
    private final Map<String, ExecutionRecord> executions = new LinkedHashMap<>();
    private Integer errorCode;
    private String errorMessage;
    private final List<String> warnings = new ArrayList<>();

    private final Instant createdAt;
    private Instant updatedAt;
    private long version;

    public OrderRecord(Instant createdAt) {
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    /**
     * Record of an order placed by the facade, before it's sent to TWS.
     */
    public static OrderRecord placed(int orderId, int clientId, String clientOrderId, Contract contract, Order order) {
        OrderRecord record = new OrderRecord(Instant.now());
        record.orderId = orderId;
        record.clientId = clientId;
        record.clientOrderId = clientOrderId;
        record.applyContract(contract);
        record.applyOrderFields(order);
        record.remaining = record.quantity;
        return record;
    }

    /**
     * Placeholder for an order which was reserved with the client order id earlier (eg. before a restart),
     * but TWS does not know about it.
     */
    public static OrderRecord unknown(Integer orderId, String clientOrderId, Instant createdAt) {
        OrderRecord record = new OrderRecord(createdAt);
        record.orderId = orderId == null ? 0 : orderId;
        record.clientOrderId = clientOrderId;
        record.status = STATUS_UNKNOWN;
        return record;
    }

    // -- updates from the callbacks

    public synchronized void applyOpenOrder(int orderId, Contract contract, Order order, OrderState orderState) {
        if (orderId > 0) {
            this.orderId = orderId;
        }
        applyContract(contract);
        applyOrderFields(order);
        if (orderState != null && StringUtils.hasText(orderState.getStatus())) {
            applyStatusString(orderState.getStatus());
        }
        if (orderState != null && StringUtils.hasText(orderState.warningText())) {
            addWarning(orderState.warningText());
        }
        touch(true);
    }

    public synchronized void applyCompletedOrder(Contract contract, Order order, OrderState orderState) {
        applyContract(contract);
        applyOrderFields(order);
        BigDecimal filledQuantity = Decimals.toBigDecimal(order.filledQuantity());
        if (filledQuantity != null) {
            this.filled = filledQuantity;
            if (quantity != null) {
                this.remaining = quantity.subtract(filledQuantity).max(BigDecimal.ZERO);
            }
        }
        if (orderState != null && StringUtils.hasText(orderState.getStatus())) {
            applyStatusString(orderState.getStatus());
        }
        if (orderState != null && StringUtils.hasText(orderState.completedStatus())) {
            addWarning(orderState.completedStatus());
        }
        touch(true);
    }

    public synchronized void applyStatus(long permId, String status, Decimal filled, Decimal remaining,
                                         double avgFillPrice) {
        if (permId > 0) {
            this.permId = permId;
        }
        applyStatusString(status);
        BigDecimal filledQuantity = Decimals.toBigDecimal(filled);
        if (filledQuantity != null) {
            this.filled = filledQuantity;
        }
        BigDecimal remainingQuantity = Decimals.toBigDecimal(remaining);
        if (remainingQuantity != null) {
            this.remaining = remainingQuantity;
        }
        if (avgFillPrice > 0) {
            this.avgFillPrice = avgFillPrice;
        }
        touch(true);
    }

    public synchronized void applyExecution(Contract contract, Execution execution) {
        if (execution.orderId() > 0 && this.orderId == 0) {
            this.orderId = execution.orderId();
        }
        if (execution.permId() > 0) {
            this.permId = execution.permId();
        }
        if (StringUtils.hasText(execution.acctNumber())) {
            this.account = execution.acctNumber();
        }
        if (this.conid == null) {
            applyContract(contract);
        }
        ExecutionRecord previous = executions.get(execution.execId());
        executions.put(execution.execId(), new ExecutionRecord(execution.execId(), execution.time(), execution.side(),
                Decimals.toBigDecimal(execution.shares()), execution.price(), execution.exchange(),
                previous == null ? null : previous.commission(),
                previous == null ? null : previous.commissionCurrency(),
                previous == null ? null : previous.realizedPnl()));
        // the cumulative quantity / average price of the execution is the most recent information about the fill
        BigDecimal cumQty = Decimals.toBigDecimal(execution.cumQty());
        if (cumQty != null && cumQty.compareTo(filled) > 0) {
            this.filled = cumQty;
            if (quantity != null) {
                this.remaining = quantity.subtract(cumQty).max(BigDecimal.ZERO);
            }
            if (execution.avgPrice() > 0) {
                this.avgFillPrice = execution.avgPrice();
            }
        }
        touch(true);
    }

    public synchronized void applyCommission(CommissionAndFeesReport report) {
        ExecutionRecord execution = executions.get(report.execId());
        if (execution != null) {
            executions.put(report.execId(), execution.withCommission(Decimals.orNull(report.commissionAndFees()),
                    report.currency(), Decimals.orNull(report.realizedPNL())));
            touch(false);
        }
    }

    /**
     * Error or warning reported by TWS for the order. An error which arrives before TWS acknowledged the order
     * means it was rejected.
     */
    public synchronized void applyError(int code, String message, boolean warning) {
        if (warning) {
            addWarning(code + ": " + message);
            touch(false);
            return;
        }
        this.errorCode = code;
        this.errorMessage = message;
        if (!acknowledged || OrderStatus.PendingSubmit.name().equals(status)
                || OrderStatus.ApiPending.name().equals(status)) {
            this.status = STATUS_REJECTED;
        }
        touch(true);
    }

    /**
     * Number of updates applied to the record so far.
     */
    public synchronized long getVersion() {
        return version;
    }

    /**
     * Waits until TWS sends any status, order or error callback for the order after the given version,
     * or the timeout elapses.
     *
     * @return true if the order was updated by TWS
     */
    public synchronized boolean awaitUpdate(long sinceVersion, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (version <= sinceVersion) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return false;
            }
            Duration wait = Duration.ofNanos(remainingNanos);
            wait(Math.max(1, wait.toMillis()));
        }
        return true;
    }

    private void applyContract(Contract contract) {
        if (contract == null) {
            return;
        }
        if (contract.conid() > 0) {
            this.conid = contract.conid();
        }
        this.symbol = firstNonEmpty(contract.symbol(), symbol);
        this.secType = contract.secType() == null ? secType : firstNonEmpty(contract.getSecType(), secType);
        this.currency = firstNonEmpty(contract.currency(), currency);
    }

    private void applyOrderFields(Order order) {
        if (order == null) {
            return;
        }
        if (order.permId() > 0) {
            this.permId = order.permId();
        }
        if (order.clientId() != 0) {
            this.clientId = order.clientId();
        }
        if (StringUtils.hasText(order.orderRef()) && clientOrderId == null) {
            this.clientOrderId = order.orderRef();
        }
        this.account = firstNonEmpty(order.account(), account);
        this.action = order.action() == null ? action : order.getAction();
        this.orderType = order.orderType() == null ? orderType : order.getOrderType();
        BigDecimal totalQuantity = Decimals.toBigDecimal(order.totalQuantity());
        if (totalQuantity != null && totalQuantity.signum() > 0) {
            this.quantity = totalQuantity;
        }
        this.limitPrice = price(order.lmtPrice(), limitPrice);
        this.stopPrice = price(order.auxPrice(), stopPrice);
        this.tif = StringUtils.hasText(order.getTif()) ? order.getTif() : tif;
        this.outsideRth = order.outsideRth();
    }

    private void applyStatusString(String newStatus) {
        if (!StringUtils.hasText(newStatus)) {
            return;
        }
        // a late callback must not resurrect a rejected order
        if (STATUS_REJECTED.equals(status) && !OrderStatus.Filled.name().equals(newStatus)) {
            return;
        }
        this.status = newStatus;
    }

    private void addWarning(String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    private void touch(boolean acknowledge) {
        this.updatedAt = Instant.now();
        if (acknowledge) {
            this.acknowledged = true;
            this.version++;
        }
        notifyAll();
    }

    // -- read access

    public synchronized OrderResponse toResponse(boolean duplicate) {
        Double commission = null;
        String commissionCurrency = null;
        Double realizedPnl = null;
        for (ExecutionRecord execution : executions.values()) {
            if (execution.commission() != null) {
                commission = (commission == null ? 0 : commission) + execution.commission();
                commissionCurrency = execution.commissionCurrency();
            }
            if (execution.realizedPnl() != null) {
                realizedPnl = (realizedPnl == null ? 0 : realizedPnl) + execution.realizedPnl();
            }
        }
        return new OrderResponse(orderId > 0 ? orderId : null, permId, clientOrderId, account, conid, symbol, secType,
                currency, action, orderType, quantity, limitPrice, stopPrice, tif, outsideRth, status, isActive(),
                filled, remaining, avgFillPrice, commission, commissionCurrency, realizedPnl,
                List.copyOf(executions.values()), errorCode, errorMessage, List.copyOf(warnings), createdAt, updatedAt,
                duplicate);
    }

    public synchronized boolean isActive() {
        return ACTIVE_STATUSES.contains(status);
    }

    public synchronized boolean isFinal() {
        return FINAL_STATUSES.contains(status);
    }

    public synchronized String getStatus() {
        return status;
    }

    public synchronized int getOrderId() {
        return orderId;
    }

    public synchronized long getPermId() {
        return permId;
    }

    public synchronized int getClientId() {
        return clientId;
    }

    public synchronized String getClientOrderId() {
        return clientOrderId;
    }

    public synchronized Integer getConid() {
        return conid;
    }

    private static Double price(double value, Double fallback) {
        Double price = Decimals.orNull(value);
        return price == null || price == 0 ? fallback : price;
    }

    private static String firstNonEmpty(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    @Override
    public synchronized String toString() {
        return "OrderRecord{orderId=" + orderId + ", permId=" + permId + ", clientOrderId=" + clientOrderId
                + ", status=" + status + ", symbol=" + Objects.toString(symbol) + "}";
    }
}
