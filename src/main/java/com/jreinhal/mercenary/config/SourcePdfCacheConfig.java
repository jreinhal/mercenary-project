package com.jreinhal.mercenary.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SourcePdfCacheConfig {

    @Bean
    public Cache<String, byte[]> sourcePdfCache(
            @Value("${sentinel.source-retention.pdf.cache.ttl-hours:1}") long ttlHours,
            @Value("${sentinel.source-retention.pdf.cache.max-total-bytes:268435456}") long maxTotalBytes) {
        return Caffeine.newBuilder()
                .maximumWeight(Math.max(1L, maxTotalBytes))
                .weigher((String key, byte[] value) -> value == null ? 0 : value.length)
                .expireAfterWrite(Duration.ofHours(Math.max(1L, ttlHours)))
                .build();
    }
}
