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
            @Value("${sentinel.source-retention.pdf.cache.max-files:100}") long maxFiles,
            @Value("${sentinel.source-retention.pdf.cache.ttl-hours:1}") long ttlHours) {
        return Caffeine.newBuilder()
                .maximumSize(Math.max(1L, maxFiles))
                .expireAfterWrite(Duration.ofHours(Math.max(1L, ttlHours)))
                .build();
    }
}
