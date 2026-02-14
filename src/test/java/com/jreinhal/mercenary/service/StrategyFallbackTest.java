package com.jreinhal.mercenary.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the RetrievalOverrides record behavior.
 * The actual strategy waterfall order is tested via E2E tests since it requires
 * the full orchestration context. These unit tests verify the override mechanism.
 */
class StrategyFallbackTest {

    @Nested
    @DisplayName("RetrievalOverrides Defaults")
    class OverrideDefaults {

        @Test
        @DisplayName("DEFAULTS should have all null overrides")
        void defaultsShouldHaveAllNull() {
            var defaults = RagOrchestrationService.RetrievalOverrides.DEFAULTS;
            assertNull(defaults.useHyde(), "Default useHyde should be null (engine decides)");
            assertNull(defaults.useGraphRag(), "Default useGraphRag should be null (engine decides)");
            assertNull(defaults.useReranking(), "Default useReranking should be null (engine decides)");
        }
    }

    @Nested
    @DisplayName("Override Allow/Disable Logic")
    class OverrideAllowDisableLogic {

        @Test
        @DisplayName("null override should allow engine (null means server config decides)")
        void nullShouldAllowEngine() {
            var overrides = new RagOrchestrationService.RetrievalOverrides(null, null, null);
            // null means "allowed" — server config determines actual use
            assertTrue(overrides.useHyde() == null || overrides.useHyde(),
                    "null useHyde should not disable the engine");
        }

        @Test
        @DisplayName("true override should allow engine")
        void trueShouldAllowEngine() {
            var overrides = new RagOrchestrationService.RetrievalOverrides(true, true, true);
            assertTrue(overrides.useHyde());
            assertTrue(overrides.useGraphRag());
            assertTrue(overrides.useReranking());
        }

        @Test
        @DisplayName("false override should disable engine")
        void falseShouldDisableEngine() {
            var overrides = new RagOrchestrationService.RetrievalOverrides(false, false, false);
            assertFalse(overrides.useHyde());
            assertFalse(overrides.useGraphRag());
            assertFalse(overrides.useReranking());
        }

        @Test
        @DisplayName("Mixed overrides should work independently")
        void mixedOverridesShouldWorkIndependently() {
            var overrides = new RagOrchestrationService.RetrievalOverrides(true, false, null);
            assertTrue(overrides.useHyde());
            assertFalse(overrides.useGraphRag());
            assertNull(overrides.useReranking());
        }
    }
}
