package hu.auxin.ibkrfacade.service;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import hu.auxin.ibkrfacade.data.holder.PositionHolder;
import hu.auxin.ibkrfacade.tws.TwsConnection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PositionManagerServiceTest {

    private final PositionManagerService service = new PositionManagerService(mock(TwsConnection.class), "");

    @Test
    void portfolioUpdatesAddMarketValueAndPnl() {
        Contract contract = new Contract();
        contract.conid(265598);
        contract.symbol("AAPL");
        contract.currency("USD");

        service.onPosition("DU1", contract, Decimal.get(10), 150.0);
        PositionHolder position = service.getAllPositions().iterator().next();
        assertThat(position.quantity()).isEqualByComparingTo("10");
        assertThat(position.marketValue()).isNull();

        service.onPortfolio("DU1", contract, Decimal.get(10), 155.0, 1550.0, 150.0, 50.0, Double.MAX_VALUE);
        position = service.getAllPositions().iterator().next();
        assertThat(position.marketPrice()).isEqualTo(155.0);
        assertThat(position.marketValue()).isEqualTo(1550.0);
        assertThat(position.unrealizedPnl()).isEqualTo(50.0);
        assertThat(position.realizedPnl()).isNull();

        // a later position callback keeps the market data
        service.onPosition("DU1", contract, Decimal.get(12), 151.0);
        position = service.getAllPositions().iterator().next();
        assertThat(position.quantity()).isEqualByComparingTo("12");
        assertThat(position.marketValue()).isEqualTo(1550.0);
        assertThat(service.getQuantityByConid(265598)).isEqualByComparingTo("12");

        service.onPosition("DU1", contract, Decimal.ZERO, 0);
        assertThat(service.getAllPositions()).isEmpty();
        assertThat(service.getQuantityByConid(265598)).isNull();
    }
}
