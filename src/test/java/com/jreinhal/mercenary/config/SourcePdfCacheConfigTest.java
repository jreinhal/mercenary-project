package com.jreinhal.mercenary.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.Test;

class SourcePdfCacheConfigTest {

    @Test
    void createsSourcePdfCacheWithConfiguredLimits() {
        SourcePdfCacheConfig config = new SourcePdfCacheConfig();
        Cache<String, byte[]> cache = config.sourcePdfCache(1L, 1024L);
        assertNotNull(cache);
    }
}
