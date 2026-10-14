package hu.auxin.ibkrfacade.order;

import com.ib.client.CommissionAndFeesReport;
import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Execution;
import com.ib.client.Order;
import com.ib.client.Types;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderRecordTest {

    @Test
    void executionsAndCommissionsAreAggregated() {
        Contract contract = new Contract();
        contract.conid(265598);
        contract.symbol("AAPL");
        Order order = new Order();
        order.action(Types.Action.BUY);
        order.totalQuantity(Decimal.get(10));
        OrderRecord record = OrderRecord.placed(100, 1, "c-1", contract, order);

        record.applyExecution(contract, execution("e1", 4, 4, 150.0, 150.0));
        record.applyExecution(contract, execution("e2", 6, 10, 151.0, 150.6));
        record.applyCommission(commission("e1", 1.0, Double.MAX_VALUE));
        record.applyCommission(commission("e2", 1.2, Double.MAX_VALUE));
        record.applyStatus(777L, "Filled", Decimal.get(10), Decimal.ZERO, 150.6);

        OrderResponse response = record.toResponse(false);
        assertThat(response.status()).isEqualTo("Filled");
        assertThat(response.active()).isFalse();
        assertThat(response.filledQuantity()).isEqualByComparingTo("10");
        assertThat(response.remainingQuantity()).isEqualByComparingTo("0");
        assertThat(response.avgFillPrice()).isEqualTo(150.6);
        assertThat(response.commission()).isEqualTo(2.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(response.commissionCurrency()).isEqualTo("USD");
        assertThat(response.realizedPnl()).isNull();
        assertThat(response.executions()).hasSize(2);
        assertThat(response.permId()).isEqualTo(777L);
    }

    @Test
    void lateStatusDoesNotResurrectARejectedOrder() {
        OrderRecord record = OrderRecord.placed(100, 1, "c-2", new Contract(), new Order());
        record.applyError(201, "rejected", false);
        record.applyStatus(0, "PreSubmitted", Decimal.ZERO, Decimal.ZERO, 0);
        assertThat(record.getStatus()).isEqualTo(OrderRecord.STATUS_REJECTED);
    }

    @Test
    void warningsDoNotRejectTheOrder() {
        OrderRecord record = OrderRecord.placed(100, 1, "c-3", new Contract(), new Order());
        record.applyError(399, "Order Message: warning", true);
        assertThat(record.getStatus()).isEqualTo("PendingSubmit");
        assertThat(record.toResponse(false).warnings()).containsExactly("399: Order Message: warning");
    }

    private static Execution execution(String execId, int shares, int cumQty, double price, double avgPrice) {
        Execution execution = new Execution();
        execution.execId(execId);
        execution.orderId(100);
        execution.permId(777L);
        execution.shares(Decimal.get(shares));
        execution.cumQty(Decimal.get(cumQty));
        execution.price(price);
        execution.avgPrice(avgPrice);
        execution.side("BOT");
        return execution;
    }

    private static CommissionAndFeesReport commission(String execId, double commission, double realizedPnl) {
        CommissionAndFeesReport report = new CommissionAndFeesReport();
        report.execId(execId);
        report.commissionAndFees(commission);
        report.currency("USD");
        report.realizedPNL(realizedPnl);
        return report;
    }
}
