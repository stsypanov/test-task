package tsypanov.executor;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConcurrentResourcePool<R> implements ResourcePool<R> {
    private final AtomicBoolean opened = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ConcurrentMap<R, ResourceWrapper<R>> resources = new ConcurrentHashMap<>();

    @Override
    public void open() {
        if (opened.getAndSet(true)) {
            throw new UnsupportedOperationException("Pool cannot be opened twice!");
        }
    }

    @Override
    public boolean isOpen() {
        return opened.get() && !closed.get();
    }

    @Override
    public void close() {
        setClosed();

        if (resources.isEmpty()) {
            return;
        }

        boolean anyAcquired = true;
        while (anyAcquired) {
            anyAcquired = resources.values().stream().anyMatch(ResourceWrapper::isAcquired);
        }
    }

    @Override
    public R acquire() {
        verifyOpened();

        while (true) {
            for (Map.Entry<R, ResourceWrapper<R>> entry : resources.entrySet()) {
                synchronized (entry) {
                    if (entry.getValue().tryAcquire()) {
                        return entry.getKey();
                    }
                }
            }
        }
    }

    @Override
    public R acquire(final long timeout, final TimeUnit timeUnit) {
        verifyOpened();

        if (timeout <= 0) {
            throw new IllegalArgumentException("Timeout cannot be negative or zero");
        }
        if (timeUnit == null) {
            throw new IllegalArgumentException("Time unit cannot be null");
        }

        final ExecutorService executorService = Executors.newSingleThreadExecutor();
        try {
            return executorService.submit(() -> {
                while (true) {
                    for (Map.Entry<R, ResourceWrapper<R>> entry : resources.entrySet()) {
                        if (entry.getValue().tryAcquire()) {
                            return entry.getKey();
                        }
                    }
                }
            }).get(timeout, timeUnit);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdown();
        }
    }

    @Override
    public void release(final R resource) {
        resources.get(resource).release();
    }

    @Override
    public boolean add(final R resource) {
        Objects.requireNonNull(resource);
        return resources.putIfAbsent(resource, new ResourceWrapper<>(resource)) == null;
    }

    @Override
    public boolean remove(final R resource) {
        Objects.requireNonNull(resource);
        final ResourceWrapper<R> resourceWrapper = resources.get(resource);
        while (resourceWrapper.isAcquired()) {
            Thread.onSpinWait();
        }
        return resources.remove(resource) != null;
    }

    @Override
    public void closeNow() {
        setClosed();

        resources.clear();
    }

    private void verifyOpened() {
        if (closed.get()) {
            throw new UnsupportedOperationException("Resource cannot be acquired from a closed pool");
        }
    }

    private void setClosed() {
        if (closed.getAndSet(true)) {
            throw new UnsupportedOperationException("Pool cannot be closed twice!");
        }
    }
}
