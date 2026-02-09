package com.jreinhal.mercenary.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures thread pools for RAG retrieval and reranking operations.
 *
 * <p>Default settings (4/8 threads) are appropriate for local development.
 * For enterprise deployments (500+ users), override via environment variables
 * or the {@code enterprise} Spring profile (application-enterprise.yaml).</p>
 *
 * <p>Uses a custom rejection handler that logs and throws {@link RejectedExecutionException}
 * instead of CallerRunsPolicy, preventing HTTP request threads from being blocked
 * when the queue is full under heavy load.</p>
 */
@Configuration
public class RagPerformanceConfig {

    private static final Logger log = LoggerFactory.getLogger(RagPerformanceConfig.class);

    @Bean(name = {"ragExecutor"}, destroyMethod = "shutdown")
    public ThreadPoolExecutor ragExecutor(
            @Value("${sentinel.performance.rag-core-threads:4}") int coreThreads,
            @Value("${sentinel.performance.rag-max-threads:8}") int maxThreads,
            @Value("${sentinel.performance.rag-queue-capacity:200}") int queueCapacity) {
        return this.buildExecutor("rag-exec-", coreThreads, maxThreads, queueCapacity);
    }

    @Bean(name = {"rerankerExecutor"}, destroyMethod = "shutdown")
    public ThreadPoolExecutor rerankerExecutor(
            @Value("${sentinel.performance.reranker-threads:4}") int threads) {
        return this.buildExecutor("rerank-exec-", threads, threads, Math.max(50, threads * 10));
    }

    private ThreadPoolExecutor buildExecutor(String prefix, int coreThreads, int maxThreads, int queueCapacity) {
        int core = Math.max(1, coreThreads);
        int max = Math.max(core, maxThreads);
        int queue = Math.max(10, queueCapacity);
        ThreadFactory threadFactory = new NamedThreadFactory(prefix);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(core, max, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), threadFactory, new MonitoredRejectionHandler(prefix));
        executor.allowCoreThreadTimeOut(true);
        log.info("Thread pool '{}' initialized: core={}, max={}, queue={}", prefix, core, max, queue);
        return executor;
    }

    /**
     * Rejection handler that logs overload conditions and throws {@link RejectedExecutionException}.
     *
     * <p>Unlike {@link ThreadPoolExecutor.CallerRunsPolicy}, this handler does NOT execute the
     * rejected task on the caller thread. This prevents the HTTP request thread from being
     * blocked by slow RAG operations when the queue is full, which would otherwise freeze
     * the UI for all users.</p>
     *
     * <p>Callers should catch {@link RejectedExecutionException} and return HTTP 503
     * (Service Unavailable) to the client.</p>
     */
    public static final class MonitoredRejectionHandler implements RejectedExecutionHandler {
        private final String poolName;
        private final AtomicLong rejectionCount = new AtomicLong(0);

        public MonitoredRejectionHandler(String poolName) {
            this.poolName = poolName;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            long count = this.rejectionCount.incrementAndGet();
            log.warn("Task rejected from pool '{}' — queue full. "
                    + "active={}, poolSize={}, queueSize={}, totalRejections={}",
                    this.poolName, executor.getActiveCount(), executor.getPoolSize(),
                    executor.getQueue().size(), count);
            throw new RejectedExecutionException(
                    "Thread pool '" + this.poolName + "' overloaded (rejected " + count + " tasks). "
                    + "Consider increasing pool size or queue capacity.");
        }

        public long getRejectionCount() {
            return this.rejectionCount.get();
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final String prefix;
        private final AtomicInteger counter = new AtomicInteger(0);

        private NamedThreadFactory(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable);
            thread.setName(this.prefix + this.counter.incrementAndGet());
            return thread;
        }
    }
}
