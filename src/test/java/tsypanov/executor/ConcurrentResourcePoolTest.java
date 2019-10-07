package tsypanov.executor;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConcurrentResourcePoolTest {

    @Test
    public void open_plain() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.open();
        try {
            pool.open();
            fail();
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void open_concurrent_expectSubsequentOpenCallsFail() throws InterruptedException {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        AtomicInteger failedCount = new AtomicInteger(0);
        final ExecutorService executor = Executors.newFixedThreadPool(5);
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    pool.open();
                } catch (UnsupportedOperationException e) {
                    failedCount.incrementAndGet();
                }
            });
        }
        executor.awaitTermination(2, SECONDS);

        assertEquals(4, failedCount.get());
    }

    @Test
    public void isOpen() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        assertFalse(pool.isOpen());

        pool.open();

        assertTrue(pool.isOpen());

        pool.close();

        assertFalse(pool.isOpen());
    }

    @Test
    public void close_plain() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.open();
        pool.close();
        assertFalse(pool.isOpen());
    }

    @Test
    public void close_expectSubsequentCloseIsNotAllowed() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.open();
        pool.close();
        assertFalse(pool.isOpen());
        try {
            pool.close();
            fail();
        } catch (UnsupportedOperationException ignored) {
        }
    }

    @Test
    public void close_notOpened() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.close();
        assertFalse(pool.isOpen());
    }

    @Test
    public void acquire() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.add(1);
        pool.add(2);

        List<Integer> acquired = Arrays.asList(pool.acquire(), pool.acquire());

        assertThat(acquired, is(Arrays.asList(1, 2)));
        pool.release(1);
        pool.release(2);
    }

    @Test
    public void acquire_testWaitingForRelease() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.add(1);

        Integer acquired = pool.acquire();

        Executors.newSingleThreadExecutor().submit(() -> {
            sleep(2L);
            pool.release(acquired);
        });

        long start = System.currentTimeMillis();
        Integer newlyAcquiredResource = pool.acquire();
        long stop = System.currentTimeMillis();

        long elapsedOnAcquire = MILLISECONDS.toSeconds(stop - start);
        assertThat(elapsedOnAcquire, is(2L));

        pool.release(newlyAcquiredResource);
    }

    @Test
    public void testAcquire_timed_expectExceptionAsResourceNotReleasedAtAll() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.add(1);

        pool.acquire();

        try {
            pool.acquire(1, SECONDS);
            fail();
        } catch (Exception e) {
            assertTrue(e.getCause() instanceof TimeoutException);
        }
    }

    @Test(timeout = 2100L)
    public void release() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.add(1);

        Integer acquired = pool.acquire();

        Executors.newSingleThreadExecutor().submit(() -> {
            sleep(2);
            pool.release(acquired);
        });

        assertNotNull(pool.acquire());
    }

    @Test(expected = NullPointerException.class)
    public void release_null() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.add(1);

        pool.release(null);
    }

    @Test(timeout = 2100L)
    public void add() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();

        Executors.newSingleThreadExecutor().submit(() -> {
            sleep(2);
            pool.add(1);
        });

        assertNotNull(pool.acquire());
    }

    @Test
    public void add_andAcquireInSeparateThreads() throws Exception {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        pool.add(1);

        pool.acquire();

        Executors.newSingleThreadExecutor().submit(() -> {
            sleep(2);
            pool.add(2);
        });

        Integer anotherAcquired = Executors
                .newSingleThreadExecutor()
                .submit((Callable<Integer>) pool::acquire)
                .get(3, SECONDS);

        assertThat(anotherAcquired, is(2));
    }

    @Test(timeout = 2100L)
    public void remove() {
        ConcurrentResourcePool<Integer> pool = new ConcurrentResourcePool<>();
        assertTrue(pool.add(1));

        Integer acquired = pool.acquire();

        Executors.newSingleThreadExecutor().submit(() -> {
            sleep(2);
            pool.release(acquired);
        });

        long elapsed = measureElapsedTimeMillis(() -> assertTrue(pool.remove(acquired)));
        assertThat(MILLISECONDS.toSeconds(elapsed), is(2L));
    }

    private static void sleep(long seconds) {
        try {
            SECONDS.sleep(seconds);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        }
    }

    private static long measureElapsedTimeMillis(Runnable runnable) {
        long start = System.currentTimeMillis();
        runnable.run();
        return System.currentTimeMillis() - start;
    }
}