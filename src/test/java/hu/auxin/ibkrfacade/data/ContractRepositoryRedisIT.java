package hu.auxin.ibkrfacade.data;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.Types;
import hu.auxin.ibkrfacade.data.holder.ContractHolder;
import hu.auxin.ibkrfacade.order.OrderReservation;
import hu.auxin.ibkrfacade.order.RedisIdempotencyStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test against a real Redis. Run it with: REDIS_IT=true REDIS_PORT=6379 mvn test
 */
@EnabledIfEnvironmentVariable(named = "REDIS_IT", matches = "true")
@SpringBootTest(properties = {"ibkr.tws.port=1", "ibkr.tws.reconnect-interval=1h"})
class ContractRepositoryRedisIT {

    @Autowired
    ContractRepository contractRepository;

    @Autowired
    RedisIdempotencyStore idempotencyStore;

    @Test
    void contractHolderWithDecimalsSurvivesRoundTrip() {
        Contract contract = new Contract();
        contract.conid(265598);
        contract.symbol("AAPL");
        contract.secType(Types.SecType.STK);
        contract.currency("USD");
        contract.exchange("SMART");
        ContractDetails details = new ContractDetails();
        details.contract(contract);
        details.longName("APPLE INC");
        details.timeZoneId("US/Eastern");
        details.minTick(0.01);
        details.minSize(Decimal.get(new BigDecimal("0.0001")));
        details.sizeIncrement(Decimal.ONE);
        ContractHolder holder = new ContractHolder(contract, details);
        holder.setStreamRequestId(1_000_000_001);

        contractRepository.save(holder);
        ContractHolder loaded = contractRepository.findById(265598).orElseThrow();

        assertThat(loaded.getContract().symbol()).isEqualTo("AAPL");
        assertThat(loaded.getContract().getSecType()).isEqualTo("STK");
        assertThat(loaded.getDetails().longName()).isEqualTo("APPLE INC");
        assertThat(loaded.getDetails().minSize().value()).isEqualByComparingTo("0.0001");
        assertThat(loaded.getDetails().sizeIncrement().value()).isEqualByComparingTo("1");
        assertThat(loaded.getStreamRequestId()).isEqualTo(1_000_000_001);
    }

    @Test
    void clientOrderIdCanBeReservedOnlyOnce() {
        String clientOrderId = "it-" + UUID.randomUUID();
        OrderReservation reservation = new OrderReservation(clientOrderId, 42, "fp", Instant.now());
        assertThat(idempotencyStore.reserve(reservation)).isTrue();
        assertThat(idempotencyStore.reserve(new OrderReservation(clientOrderId, 43, "fp2", Instant.now()))).isFalse();
        OrderReservation loaded = idempotencyStore.find(clientOrderId).orElseThrow();
        assertThat(loaded.orderId()).isEqualTo(42);
        assertThat(loaded.fingerprint()).isEqualTo("fp");
    }
}
