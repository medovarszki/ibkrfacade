package hu.auxin.ibkrfacade.tws;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwsRequestRegistryTest {

    private final TwsRequestRegistry registry = new TwsRequestRegistry(Duration.ofSeconds(2));

    @Test
    void requestIdsDoNotOverlapWithOrderIds() {
        int id = registry.nextId();
        assertThat(id).isGreaterThanOrEqualTo(TwsRequestRegistry.REQUEST_ID_BASE);
        assertThat(TwsRequestRegistry.isRequestId(id)).isTrue();
        assertThat(TwsRequestRegistry.isRequestId(12345)).isFalse();
        assertThat(registry.nextId()).isEqualTo(id + 1);
    }

    @Test
    void collectsItemsUntilEnd() {
        List<String> result = registry.call(id -> CompletableFuture.runAsync(() -> {
            registry.addItem(id, "a");
            registry.addItem(id, "b");
            registry.complete(id);
        }));
        assertThat(result).containsExactly("a", "b");
    }

    @Test
    void errorFailsTheRequest() {
        assertThatThrownBy(() -> registry.call(id -> CompletableFuture.runAsync(
                () -> registry.fail(id, new TwsException(200, "No security definition")))))
                .isInstanceOf(TwsException.class)
                .hasMessageContaining("No security definition")
                .satisfies(e -> assertThat(((TwsException) e).getCode()).isEqualTo(200));
    }

    @Test
    void timesOutAndForgetsTheRequest() {
        AtomicInteger requestId = new AtomicInteger();
        assertThatThrownBy(() -> registry.call(requestId::set, Duration.ofMillis(50)))
                .isInstanceOf(TwsTimeoutException.class);
        assertThat(registry.isPending(requestId.get())).isFalse();
        // late callbacks are ignored
        registry.addItem(requestId.get(), "late");
        registry.complete(requestId.get());
        assertThat(registry.fail(requestId.get(), new TwsException(1, "late"))).isFalse();
    }

    @Test
    void senderExceptionReleasesTheRequest() {
        AtomicInteger requestId = new AtomicInteger();
        assertThatThrownBy(() -> registry.call(id -> {
            requestId.set(id);
            throw new TwsNotConnectedException("not connected");
        })).isInstanceOf(TwsNotConnectedException.class);
        assertThat(registry.isPending(requestId.get())).isFalse();
    }

    @Test
    void failAllFailsEveryPendingRequest() {
        assertThatThrownBy(() -> registry.call(id -> CompletableFuture.runAsync(
                () -> registry.failAll(new TwsNotConnectedException("closed")))))
                .isInstanceOf(TwsNotConnectedException.class);
    }
}
