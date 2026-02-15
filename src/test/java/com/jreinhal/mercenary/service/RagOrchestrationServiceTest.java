package com.jreinhal.mercenary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RagOrchestrationServiceTest {

    @Nested
    @DisplayName("Adaptive Token Budget - adjustForComplexity")
    class AdjustForComplexityTest {

        private RagOrchestrationService createServiceWithBudgetDefaults() {
            RagOrchestrationService service = mock(RagOrchestrationService.class, CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(service, "noRetrievalMaxTokens", 128);
            ReflectionTestUtils.setField(service, "noRetrievalNumCtx", 2048);
            ReflectionTestUtils.setField(service, "chunkMaxTokens", 256);
            ReflectionTestUtils.setField(service, "chunkNumCtx", 4096);
            return service;
        }

        @Test
        @DisplayName("NO_RETRIEVAL should cap maxTokens and reduce numCtx")
        void noRetrievalShouldCapTokensAndCtx() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            RagOrchestrationService.ResponsePolicy base =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.GOVERNMENT, 768, 8192, true, true, false);

            RagOrchestrationService.ResponsePolicy adjusted =
                    service.adjustForComplexity(base, AdaptiveRagService.RoutingDecision.NO_RETRIEVAL);

            assertThat(adjusted.maxTokens()).isEqualTo(128);
            assertThat(adjusted.numCtx()).isEqualTo(2048);
            assertThat(adjusted.edition()).isEqualTo(LicenseService.Edition.GOVERNMENT);
            assertThat(adjusted.enforceCitations()).isTrue();
        }

        @Test
        @DisplayName("CHUNK should cap maxTokens and reduce numCtx")
        void chunkShouldCapTokensAndCtx() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            RagOrchestrationService.ResponsePolicy base =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.ENTERPRISE, 640, 8192, false, false, true);

            RagOrchestrationService.ResponsePolicy adjusted =
                    service.adjustForComplexity(base, AdaptiveRagService.RoutingDecision.CHUNK);

            assertThat(adjusted.maxTokens()).isEqualTo(256);
            assertThat(adjusted.numCtx()).isEqualTo(4096);
            assertThat(adjusted.edition()).isEqualTo(LicenseService.Edition.ENTERPRISE);
            assertThat(adjusted.enforceCitations()).isFalse();
        }

        @Test
        @DisplayName("DOCUMENT should pass through unchanged")
        void documentShouldPassThrough() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            RagOrchestrationService.ResponsePolicy base =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.GOVERNMENT, 768, 8192, true, true, false);

            RagOrchestrationService.ResponsePolicy adjusted =
                    service.adjustForComplexity(base, AdaptiveRagService.RoutingDecision.DOCUMENT);

            assertThat(adjusted).isSameAs(base);
        }

        @Test
        @DisplayName("Math.min: edition limit wins when lower than tier cap")
        void editionLimitWinsWhenLower() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            // TRIAL with 100 maxTokens — lower than CHUNK tier cap of 256
            RagOrchestrationService.ResponsePolicy base =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.TRIAL, 100, 4096, false, false, true);

            RagOrchestrationService.ResponsePolicy adjusted =
                    service.adjustForComplexity(base, AdaptiveRagService.RoutingDecision.CHUNK);

            assertThat(adjusted.maxTokens()).isEqualTo(100);  // edition limit wins
        }

        @Test
        @DisplayName("Null decision returns base policy unchanged")
        void nullDecisionReturnsBase() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            RagOrchestrationService.ResponsePolicy base =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.TRIAL, 512, 4096, false, false, true);

            RagOrchestrationService.ResponsePolicy result =
                    service.adjustForComplexity(base, null);

            assertThat(result).isSameAs(base);
        }

        @Test
        @DisplayName("Null base returns null")
        void nullBaseReturnsNull() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            RagOrchestrationService.ResponsePolicy result =
                    service.adjustForComplexity(null, AdaptiveRagService.RoutingDecision.CHUNK);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("Preserves citation and evidence settings across all tiers")
        void preservesCitationSettings() {
            RagOrchestrationService service = createServiceWithBudgetDefaults();
            RagOrchestrationService.ResponsePolicy base =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.MEDICAL, 768, 8192, true, true, false);

            for (AdaptiveRagService.RoutingDecision decision : AdaptiveRagService.RoutingDecision.values()) {
                RagOrchestrationService.ResponsePolicy adjusted =
                        service.adjustForComplexity(base, decision);
                assertThat(adjusted.enforceCitations()).isTrue();
                assertThat(adjusted.appendEvidenceAlways()).isTrue();
                assertThat(adjusted.appendEvidenceWhenNoCitations()).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("ResponsePolicy record with numCtx")
    class ResponsePolicyTest {

        @Test
        @DisplayName("Record should store numCtx field")
        void shouldStoreNumCtx() {
            RagOrchestrationService.ResponsePolicy policy =
                    new RagOrchestrationService.ResponsePolicy(
                            LicenseService.Edition.GOVERNMENT, 768, 8192, true, true, false);

            assertThat(policy.numCtx()).isEqualTo(8192);
            assertThat(policy.maxTokens()).isEqualTo(768);
        }
    }

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
