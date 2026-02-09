package com.jreinhal.mercenary.config;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagPerformanceConfigTest {

    private ThreadPoolExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCreateRagExecutorWithConfiguredValues() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.ragExecutor(4, 8, 200);

        assertEquals(4, executor.getCorePoolSize());
        assertEquals(8, executor.getMaximumPoolSize());
        assertTrue(executor.allowsCoreThreadTimeOut());
    }

    @Test
    void shouldCreateRerankerExecutorWithConfiguredValues() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.rerankerExecutor(6);

        assertEquals(6, executor.getCorePoolSize());
        assertEquals(6, executor.getMaximumPoolSize());
        assertTrue(executor.allowsCoreThreadTimeOut());
    }

    @Test
    void shouldEnforceMinimumPoolValues() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.ragExecutor(0, -1, 5);

        // Minimum core = 1, max >= core, queue >= 10
        assertEquals(1, executor.getCorePoolSize());
        assertTrue(executor.getMaximumPoolSize() >= 1);
    }

    @Test
    void shouldEnforceMinimumQueueCapacity() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.ragExecutor(1, 1, 1);

        // Minimum queue = 10
        assertEquals(10, executor.getQueue().remainingCapacity());
    }

    @Test
    void shouldUseMonitoredRejectionHandler() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.ragExecutor(2, 2, 10);

        assertInstanceOf(RagPerformanceConfig.MonitoredRejectionHandler.class,
                executor.getRejectedExecutionHandler());
    }

    @Test
    void shouldRejectTaskWhenPoolAndQueueFull() throws InterruptedException {
        // Create executor directly with queue size 1 to bypass minimum enforcement
        RagPerformanceConfig.MonitoredRejectionHandler handler =
                new RagPerformanceConfig.MonitoredRejectionHandler("test-pool");
        executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), handler);

        CountDownLatch blockLatch = new CountDownLatch(1);

        // Fill the single thread
        executor.submit(() -> {
            try { blockLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread.sleep(50);

        // Fill the single queue slot
        executor.submit(() -> {
            try { blockLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        // Third task should be rejected (pool full + queue full)
        assertThrows(RejectedExecutionException.class, () ->
                executor.submit(() -> {}));

        assertEquals(1, handler.getRejectionCount());

        blockLatch.countDown();
    }

    @Test
    void shouldIncrementRejectionCountOnMultipleRejections() throws InterruptedException {
        RagPerformanceConfig.MonitoredRejectionHandler handler =
                new RagPerformanceConfig.MonitoredRejectionHandler("test-pool");
        executor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), handler);

        CountDownLatch blockLatch = new CountDownLatch(1);

        // Fill pool and queue
        executor.submit(() -> {
            try { blockLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        Thread.sleep(50);
        executor.submit(() -> {
            try { blockLatch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });

        // Submit 3 tasks that should all be rejected
        for (int i = 0; i < 3; i++) {
            try {
                executor.submit(() -> {});
            } catch (RejectedExecutionException ignored) {
                // Expected
            }
        }

        assertEquals(3, handler.getRejectionCount());

        blockLatch.countDown();
    }

    @Test
    void shouldNotRejectWhenQueueHasCapacity() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.ragExecutor(2, 4, 100);

        // Should not throw — queue has plenty of capacity
        assertDoesNotThrow(() -> {
            for (int i = 0; i < 10; i++) {
                executor.submit(() -> {});
            }
        });
    }

    @Test
    void shouldNameThreadsWithPrefix() throws Exception {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.ragExecutor(1, 1, 10);

        String[] threadName = new String[1];
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            threadName[0] = Thread.currentThread().getName();
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(threadName[0].startsWith("rag-exec-"),
                "Thread name should start with 'rag-exec-' but was: " + threadName[0]);
    }

    @Test
    void shouldNameRerankerThreadsWithPrefix() throws Exception {
        RagPerformanceConfig config = new RagPerformanceConfig();
        executor = config.rerankerExecutor(1);

        String[] threadName = new String[1];
        CountDownLatch latch = new CountDownLatch(1);

        executor.submit(() -> {
            threadName[0] = Thread.currentThread().getName();
            latch.countDown();
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertTrue(threadName[0].startsWith("rerank-exec-"),
                "Thread name should start with 'rerank-exec-' but was: " + threadName[0]);
    }
}
