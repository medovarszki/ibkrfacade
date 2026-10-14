package hu.auxin.ibkrfacade.tws;

import com.ib.client.EClientSocket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TwsConnectionTest {

    private TwsConnection connection;
    private EClientSocket socket;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        connection = new TwsConnection("localhost", 4002, 7, Duration.ofSeconds(10), mock(ObjectProvider.class),
                mock(ApplicationEventPublisher.class), new TwsRequestRegistry(Duration.ofSeconds(1)));
        socket = mock(EClientSocket.class);
        when(socket.isConnected()).thenReturn(true);
    }

    @Test
    void noOrderIdBeforeNextValidId() {
        assertThatThrownBy(connection::allocateOrderId).isInstanceOf(TwsNotConnectedException.class);
        connection.attach(socket);
        assertThatThrownBy(connection::allocateOrderId).isInstanceOf(TwsNotConnectedException.class);
    }

    @Test
    void orderIdsStartFromNextValidIdAndNeverGoBack() {
        connection.attach(socket);
        connection.onNextValidId(100);
        assertThat(connection.allocateOrderId()).isEqualTo(100);
        assertThat(connection.allocateOrderId()).isEqualTo(101);
        // a lower nextValidId (eg. answer to reqIds) must not cause reuse
        connection.onNextValidId(50);
        assertThat(connection.allocateOrderId()).isEqualTo(102);
        // a higher one moves the sequence forward
        connection.onNextValidId(500);
        assertThat(connection.allocateOrderId()).isEqualTo(500);
    }

    @Test
    void orderIdsAreUniqueAcrossThreads() throws Exception {
        connection.attach(socket);
        connection.onNextValidId(1);
        Set<Integer> ids = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futures.add(executor.submit(() -> {
                for (int j = 0; j < 1000; j++) {
                    ids.add(connection.allocateOrderId());
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();
        assertThat(ids).hasSize(8000);
    }

    @Test
    void detectsPaperAndLiveAccounts() {
        assertThat(connection.getAccountMode()).isEqualTo(AccountMode.UNKNOWN);
        connection.onManagedAccounts("DU1234567,");
        assertThat(connection.getAccountMode()).isEqualTo(AccountMode.PAPER);
        assertThat(connection.getAccounts()).containsExactly("DU1234567");
        connection.onManagedAccounts("DU1234567,U7654321");
        assertThat(connection.getAccountMode()).isEqualTo(AccountMode.LIVE);
        connection.onManagedAccounts("U7654321");
        assertThat(connection.getAccountMode()).isEqualTo(AccountMode.LIVE);
    }

    @Test
    void masksAccountIds() {
        assertThat(TwsConnection.maskAccount("DU1234567")).isEqualTo("DU****567");
        assertThat(TwsConnection.maskAccount("U12")).isEqualTo("****");
    }
}
