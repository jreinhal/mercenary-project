package com.jreinhal.mercenary.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class RagOrchestrationServiceTest {

    @Nested
    @DisplayName("RetrievalOverrides record")
    class RetrievalOverridesTest {

        @Test
        @DisplayName("DEFAULTS should have all null fields")
        void defaultsShouldHaveAllNullFields() {
            RagOrchestrationService.RetrievalOverrides defaults =
                    RagOrchestrationService.RetrievalOverrides.DEFAULTS;

            assertThat(defaults.useHyde()).isNull();
            assertThat(defaults.useGraphRag()).isNull();
            assertThat(defaults.useReranking()).isNull();
        }

        @Test
        @DisplayName("Should store explicit true values")
        void shouldStoreExplicitTrue() {
            RagOrchestrationService.RetrievalOverrides overrides =
                    new RagOrchestrationService.RetrievalOverrides(true, true, true);

            assertThat(overrides.useHyde()).isTrue();
            assertThat(overrides.useGraphRag()).isTrue();
            assertThat(overrides.useReranking()).isTrue();
        }

        @Test
        @DisplayName("Should store explicit false values")
        void shouldStoreExplicitFalse() {
            RagOrchestrationService.RetrievalOverrides overrides =
                    new RagOrchestrationService.RetrievalOverrides(false, false, false);

            assertThat(overrides.useHyde()).isFalse();
            assertThat(overrides.useGraphRag()).isFalse();
            assertThat(overrides.useReranking()).isFalse();
        }

        @Test
        @DisplayName("Should support mixed null and explicit values")
        void shouldSupportMixedValues() {
            RagOrchestrationService.RetrievalOverrides overrides =
                    new RagOrchestrationService.RetrievalOverrides(false, null, true);

            assertThat(overrides.useHyde()).isFalse();
            assertThat(overrides.useGraphRag()).isNull();
            assertThat(overrides.useReranking()).isTrue();
        }

        @Test
        @DisplayName("Null-coalescing logic: null defaults to allowed (true)")
        void nullShouldDefaultToAllowed() {
            RagOrchestrationService.RetrievalOverrides overrides =
                    RagOrchestrationService.RetrievalOverrides.DEFAULTS;

            // This mirrors the logic in retrieveContext():
            // boolean hydeAllowed = overrides.useHyde() == null || overrides.useHyde();
            boolean hydeAllowed = overrides.useHyde() == null || overrides.useHyde();
            boolean graphRagAllowed = overrides.useGraphRag() == null || overrides.useGraphRag();
            boolean rerankingAllowed = overrides.useReranking() == null || overrides.useReranking();

            assertThat(hydeAllowed).isTrue();
            assertThat(graphRagAllowed).isTrue();
            assertThat(rerankingAllowed).isTrue();
        }

        @Test
        @DisplayName("Explicit false should disable engine")
        void explicitFalseShouldDisable() {
            RagOrchestrationService.RetrievalOverrides overrides =
                    new RagOrchestrationService.RetrievalOverrides(false, false, false);

            boolean hydeAllowed = overrides.useHyde() == null || overrides.useHyde();
            boolean graphRagAllowed = overrides.useGraphRag() == null || overrides.useGraphRag();
            boolean rerankingAllowed = overrides.useReranking() == null || overrides.useReranking();

            assertThat(hydeAllowed).isFalse();
            assertThat(graphRagAllowed).isFalse();
            assertThat(rerankingAllowed).isFalse();
        }

        @Test
        @DisplayName("Record equals and hashCode should work correctly")
        void shouldHaveCorrectEquality() {
            RagOrchestrationService.RetrievalOverrides a =
                    new RagOrchestrationService.RetrievalOverrides(true, false, null);
            RagOrchestrationService.RetrievalOverrides b =
                    new RagOrchestrationService.RetrievalOverrides(true, false, null);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        @DisplayName("Record toString should include field names")
        void shouldHaveReadableToString() {
            RagOrchestrationService.RetrievalOverrides overrides =
                    new RagOrchestrationService.RetrievalOverrides(true, false, null);

            String str = overrides.toString();
            assertThat(str).contains("useHyde");
            assertThat(str).contains("useGraphRag");
            assertThat(str).contains("useReranking");
        }
    }
}
