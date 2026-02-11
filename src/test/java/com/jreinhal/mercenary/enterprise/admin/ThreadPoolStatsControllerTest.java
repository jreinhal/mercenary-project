package com.jreinhal.mercenary.enterprise.admin;

import com.jreinhal.mercenary.config.RagPerformanceConfig;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

class ThreadPoolStatsControllerTest {

    private ThreadPoolExecutor ragExecutor;
    private ThreadPoolExecutor rerankerExecutor;
    private ThreadPoolStatsController controller;

    @BeforeEach
    void setUp() {
        RagPerformanceConfig config = new RagPerformanceConfig();
        ragExecutor = config.ragExecutor(2, 4, 50);
        rerankerExecutor = config.rerankerExecutor(2);
        controller = new ThreadPoolStatsController(ragExecutor, rerankerExecutor);
    }

    @AfterEach
    void tearDown() {
        ragExecutor.shutdownNow();
        rerankerExecutor.shutdownNow();
    }

    @Test
    void shouldReturnBothExecutorStats() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("ragExecutor"));
        assertTrue(response.getBody().containsKey("rerankerExecutor"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCorrectRagPoolConfig() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();
        Map<String, Object> ragStats = (Map<String, Object>) response.getBody().get("ragExecutor");

        assertEquals(2, ragStats.get("corePoolSize"));
        assertEquals(4, ragStats.get("maxPoolSize"));
        assertEquals(0, ragStats.get("activeThreads"));
        assertEquals(0, ragStats.get("queueSize"));
        assertEquals(0L, ragStats.get("rejectionCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReturnCorrectRerankerPoolConfig() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();
        Map<String, Object> rerankerStats = (Map<String, Object>) response.getBody().get("rerankerExecutor");

        assertEquals(2, rerankerStats.get("corePoolSize"));
        assertEquals(2, rerankerStats.get("maxPoolSize"));
        assertEquals(0, rerankerStats.get("activeThreads"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeQueueCapacityMetrics() {
        ResponseEntity<Map<String, Object>> response = controller.getStats();
        Map<String, Object> ragStats = (Map<String, Object>) response.getBody().get("ragExecutor");

        assertTrue((int) ragStats.get("queueRemainingCapacity") > 0);
        assertEquals(0L, ragStats.get("completedTaskCount"));
        assertEquals(0L, ragStats.get("totalTaskCount"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReflectCompletedTasks() throws Exception {
        // Submit and wait for a task to complete
        ragExecutor.submit(() -> {}).get();
        long completed = 0L;
        long total = 0L;
        for (int i = 0; i < 40; i++) {
            ResponseEntity<Map<String, Object>> response = controller.getStats();
            Map<String, Object> ragStats = (Map<String, Object>) response.getBody().get("ragExecutor");
            completed = ((Number) ragStats.get("completedTaskCount")).longValue();
            total = ((Number) ragStats.get("totalTaskCount")).longValue();
            if (completed >= 1L && total >= 1L) {
                break;
            }
            Thread.sleep(25L);
        }

        assertTrue(completed >= 1L);
        assertTrue(total >= 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldReflectRejectionCount() throws Exception {
        // Create a tiny pool directly (bypassing buildExecutor minimum enforcement)
        ragExecutor.shutdownNow();
        RagPerformanceConfig.MonitoredRejectionHandler handler =
                new RagPerformanceConfig.MonitoredRejectionHandler("test-rag");
        ragExecutor = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1), handler);
        controller = new ThreadPoolStatsController(ragExecutor, rerankerExecutor);

        // Fill pool and queue
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch startedLatch = new CountDownLatch(1);
        ragExecutor.submit(() -> { startedLatch.countDown(); try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "First task should have started");
        ragExecutor.submit(() -> { try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });

        // Force a rejection
        try {
            ragExecutor.submit(() -> {});
        } catch (RejectedExecutionException ignored) {
            // Expected
        }

        ResponseEntity<Map<String, Object>> response = controller.getStats();
        Map<String, Object> ragStats = (Map<String, Object>) response.getBody().get("ragExecutor");

        assertEquals(1L, ragStats.get("rejectionCount"));

        latch.countDown();
    }
}
