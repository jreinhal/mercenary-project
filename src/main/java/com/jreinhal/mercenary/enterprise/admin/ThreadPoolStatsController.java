package com.jreinhal.mercenary.enterprise.admin;

import com.jreinhal.mercenary.config.RagPerformanceConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes thread pool statistics for monitoring and capacity planning.
 *
 * <p>Provides real-time visibility into the RAG and reranker thread pools,
 * including active threads, queue depth, completed tasks, and rejection counts.
 * Use this endpoint to detect overload conditions and tune pool sizes.</p>
 *
 * <p>Accessible only to ADMIN role users.</p>
 */
@RestController
@RequestMapping("/api/admin/thread-pool-stats")
@PreAuthorize("hasRole('ADMIN')")
public class ThreadPoolStatsController {

    private final ThreadPoolExecutor ragExecutor;
    private final ThreadPoolExecutor rerankerExecutor;

    public ThreadPoolStatsController(
            @Qualifier("ragExecutor") ThreadPoolExecutor ragExecutor,
            @Qualifier("rerankerExecutor") ThreadPoolExecutor rerankerExecutor) {
        this.ragExecutor = ragExecutor;
        this.rerankerExecutor = rerankerExecutor;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("ragExecutor", buildPoolStats(this.ragExecutor));
        stats.put("rerankerExecutor", buildPoolStats(this.rerankerExecutor));
        return ResponseEntity.ok(stats);
    }

    private Map<String, Object> buildPoolStats(ThreadPoolExecutor executor) {
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("corePoolSize", executor.getCorePoolSize());
        pool.put("maxPoolSize", executor.getMaximumPoolSize());
        pool.put("activeThreads", executor.getActiveCount());
        pool.put("currentPoolSize", executor.getPoolSize());
        pool.put("largestPoolSize", executor.getLargestPoolSize());
        pool.put("queueSize", executor.getQueue().size());
        pool.put("queueRemainingCapacity", executor.getQueue().remainingCapacity());
        pool.put("completedTaskCount", executor.getCompletedTaskCount());
        pool.put("totalTaskCount", executor.getTaskCount());

        // Extract rejection count from our custom handler
        if (executor.getRejectedExecutionHandler() instanceof RagPerformanceConfig.MonitoredRejectionHandler handler) {
            pool.put("rejectionCount", handler.getRejectionCount());
        }

        return pool;
    }
}
