package com.jreinhal.mercenary.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagPerformanceConfig {
    @Bean(name={"ragExecutor"}, destroyMethod="shutdown")
    public ExecutorService ragExecutor(@Value("${sentinel.performance.rag-core-threads:4}") int coreThreads,
            @Value("${sentinel.performance.rag-max-threads:8}") int maxThreads,
            @Value("${sentinel.performance.rag-queue-capacity:200}") int queueCapacity) {
        return this.buildExecutor("rag-exec-", coreThreads, maxThreads, queueCapacity);
    }

    @Bean(name={"rerankerExecutor"}, destroyMethod="shutdown")
    public ExecutorService rerankerExecutor(@Value("${sentinel.performance.reranker-threads:4}") int threads) {
        return this.buildExecutor("rerank-exec-", threads, threads, Math.max(50, threads * 10));
    }

    private ExecutorService buildExecutor(String prefix, int coreThreads, int maxThreads, int queueCapacity) {
        int core = Math.max(1, coreThreads);
        int max = Math.max(core, maxThreads);
        int queue = Math.max(10, queueCapacity);
        ThreadFactory threadFactory = new NamedThreadFactory(prefix);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(core, max, 30L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue), threadFactory, new ThreadPoolExecutor.CallerRunsPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
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
