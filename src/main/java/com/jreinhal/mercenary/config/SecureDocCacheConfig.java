package com.jreinhal.mercenary.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SecureDocCacheConfig {
    @Bean
    public Cache<String, String> secureDocCache() {
        return Caffeine.newBuilder()
            .maximumSize(100L)
            .expireAfterWrite(Duration.ofHours(1L))
            .build();
    }
}
