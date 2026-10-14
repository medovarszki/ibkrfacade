package hu.auxin.ibkrfacade.tws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * Correlates the asynchronous TWS callbacks with the requests sent by the facade.
 * <p>
 * Request (ticker) ids start from {@link #REQUEST_ID_BASE}, so they can't collide with the order ids which are
 * coming from nextValidId(). This matters because the error() callback uses the same id parameter for both.
 */
@Component
public class TwsRequestRegistry {

    public static final int REQUEST_ID_BASE = 1_000_000_000;

    private final AtomicInteger sequence = new AtomicInteger(REQUEST_ID_BASE);
    private final ConcurrentMap<Integer, TwsRequest<?>> pending = new ConcurrentHashMap<>();
    private final Duration defaultTimeout;

    public TwsRequestRegistry(@Value("${ibkr.tws.request-timeout:15s}") Duration defaultTimeout) {
        this.defaultTimeout = defaultTimeout;
    }

    public int nextId() {
        return sequence.getAndIncrement();
    }

    public static boolean isRequestId(int id) {
        return id >= REQUEST_ID_BASE;
    }

    /**
     * Sends a request with a newly allocated id and blocks until the response is complete, an error arrives
     * for the request or the default timeout is reached.
     *
     * @param sender sends the request to TWS with the given request id
     * @return the partial results added by the callbacks
     */
    public <T> List<T> call(IntConsumer sender) {
        return call(sender, defaultTimeout);
    }

    public <T> List<T> call(IntConsumer sender, Duration timeout) {
        TwsRequest<T> request = new TwsRequest<>(nextId());
        pending.put(request.id(), request);
        try {
            sender.accept(request.id());
            return request.future().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new TwsTimeoutException("No answer from TWS in " + timeout.toSeconds() + "s for request " + request.id());
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TwsTimeoutException("Interrupted while waiting for request " + request.id());
        } finally {
            pending.remove(request.id());
        }
    }

    public boolean isPending(int requestId) {
        return pending.containsKey(requestId);
    }

    /**
     * Adds a partial result to a pending request. Results for unknown (eg. timed out) requests are dropped.
     */
    public void addItem(int requestId, Object item) {
        TwsRequest<?> request = pending.get(requestId);
        if (request != null) {
            request.add(item);
        }
    }

    /**
     * Marks a pending request as complete with the partial results collected so far.
     */
    public void complete(int requestId) {
        TwsRequest<?> request = pending.get(requestId);
        if (request != null) {
            request.complete();
        }
    }

    /**
     * Fails a pending request.
     *
     * @return false if there was no pending request with the id
     */
    public boolean fail(int requestId, RuntimeException e) {
        TwsRequest<?> request = pending.get(requestId);
        if (request == null) {
            return false;
        }
        request.fail(e);
        return true;
    }

    /**
     * Fails every pending request, eg. when the connection is lost.
     */
    public void failAll(RuntimeException e) {
        pending.values().forEach(request -> request.fail(e));
    }
}
