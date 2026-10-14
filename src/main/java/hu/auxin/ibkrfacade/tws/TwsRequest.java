package hu.auxin.ibkrfacade.tws;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A pending request towards TWS. Callbacks add the partial results with the request id, and complete it when the
 * corresponding "end" callback arrives.
 */
final class TwsRequest<T> {

    private final int id;
    private final List<T> items = new ArrayList<>();
    private final CompletableFuture<List<T>> future = new CompletableFuture<>();

    TwsRequest(int id) {
        this.id = id;
    }

    int id() {
        return id;
    }

    CompletableFuture<List<T>> future() {
        return future;
    }

    @SuppressWarnings("unchecked")
    synchronized void add(Object item) {
        items.add((T) item);
    }

    synchronized void complete() {
        future.complete(List.copyOf(items));
    }

    void fail(RuntimeException e) {
        future.completeExceptionally(e);
    }
}
