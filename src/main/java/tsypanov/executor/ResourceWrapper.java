package tsypanov.executor;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

class ResourceWrapper<R> {
    private final R resource;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final Semaphore semaphore = new Semaphore(1, true);

    ResourceWrapper(R resource) {
        this.resource = Objects.requireNonNull(resource);
    }

    boolean tryAcquire() {
        boolean acquired = semaphore.tryAcquire();
        if (acquired) {
            busy.set(true);
        }
        return acquired;
    }

    void release() {
        semaphore.release();
        busy.set(false);
    }

    boolean isAcquired() {
        return busy.get();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ResourceWrapper<?> that = (ResourceWrapper<?>) o;

        return resource.equals(that.resource);
    }

    @Override
    public int hashCode() {
        return resource.hashCode();
    }
}
