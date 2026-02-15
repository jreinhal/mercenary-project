package com.jreinhal.mercenary.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.Department;
import com.jreinhal.mercenary.config.SectorConfig;
import com.jreinhal.mercenary.core.license.LicenseService;
import com.jreinhal.mercenary.rag.ModalityRouter;
import com.jreinhal.mercenary.rag.adaptiverag.AdaptiveRagService;
import com.jreinhal.mercenary.rag.agentic.AgenticRagOrchestrator;
import com.jreinhal.mercenary.rag.birag.BidirectionalRagService;
import com.jreinhal.mercenary.rag.crag.RewriteService;
import com.jreinhal.mercenary.rag.hifirag.HiFiRagService;
import com.jreinhal.mercenary.rag.hgmem.HGMemQueryEngine;
import com.jreinhal.mercenary.rag.hybridrag.HybridRagService;
import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import com.jreinhal.mercenary.rag.miarag.MiARagService;
import com.jreinhal.mercenary.rag.qucorag.QuCoRagService;
import com.jreinhal.mercenary.rag.ragpart.RagPartService;
import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.workspace.WorkspaceQuotaService;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

class Phase1PromptHardeningTest {

    @Test
    void buildInformationIncludesPageMetadataWhenAvailable() {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "maxContextChars", 4000);
        ReflectionTestUtils.setField(service, "maxDocChars", 2000);
        ReflectionTestUtils.setField(service, "maxOverviewChars", 1000);
        ReflectionTestUtils.setField(service, "maxDocs", 5);

        Document doc = new Document("hello world", Map.of("source", "file.pdf", "page_number", 3));
        String information = ReflectionTestUtils.invokeMethod(service, "buildInformation", List.of(doc), "");

        assertNotNull(information);
        assertTrue(information.contains("[file.pdf]"));
        assertTrue(information.contains("Page: 3"));
    }

    @Test
    void buildSystemPromptContainsVerificationChecklist() throws Exception {
        RagOrchestrationService service = newTestService();

        String information = "[file.pdf]\nPage: 3\nhello world";
        Class<?> policyType = Class.forName("com.jreinhal.mercenary.service.RagOrchestrationService$ResponsePolicy");
        Method m = RagOrchestrationService.class.getDeclaredMethod("buildSystemPrompt", String.class, policyType, Department.class);
        m.setAccessible(true);

        String prompt = (String) m.invoke(service, information, null, Department.ENTERPRISE);
        assertNotNull(prompt);
        assertTrue(prompt.contains("VERIFICATION (internal; do not output this checklist):"));
        assertTrue(prompt.contains("If the context contains tables"));
        assertTrue(prompt.contains("include it right after the citation"));
    }

    @Test
    void buildSystemPromptReturnsNoDocsMessageWhenInformationMissing() throws Exception {
        RagOrchestrationService service = newTestService();
        Class<?> policyType = Class.forName("com.jreinhal.mercenary.service.RagOrchestrationService$ResponsePolicy");
        Method m = RagOrchestrationService.class.getDeclaredMethod("buildSystemPrompt", String.class, policyType, Department.class);
        m.setAccessible(true);

        String prompt = (String) m.invoke(service, "   ", null, Department.ENTERPRISE);
        assertNotNull(prompt);
        assertTrue(prompt.contains("No documents are available"));
    }

    @Test
    void buildSystemPromptIncludesHypothesisDrivenReasoningMode() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "quickLookupMaxTerms", 9);
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 40);

        String information = "[alpha.pdf]\nPage: 2\ncontent";
        Class<?> policyType = Class.forName("com.jreinhal.mercenary.service.RagOrchestrationService$ResponsePolicy");
        Method m = RagOrchestrationService.class.getDeclaredMethod(
                "buildSystemPrompt", String.class, String.class, policyType, Department.class);
        m.setAccessible(true);

        String prompt = (String) m.invoke(service, "What is the operating pressure?", information, null, Department.ENTERPRISE);
        assertNotNull(prompt);
        assertTrue(prompt.contains("AGENT REASONING MODE (internal; do not output this section):"));
        assertTrue(prompt.contains("Persona: quick lookup."));
        assertTrue(prompt.contains("form 1-2 hypotheses"));
        assertTrue(prompt.contains("ask one concise clarifying question instead of assuming"));
        assertTrue(prompt.contains("roughly 40 documents"));
    }

    @Test
    void buildSystemPromptClassifiesAnalyticalAndHistoricalQueries() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "quickLookupMaxTerms", 5);
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 40);

        String information = "[alpha.pdf]\nPage: 7\ncontent";
        Class<?> policyType = Class.forName("com.jreinhal.mercenary.service.RagOrchestrationService$ResponsePolicy");
        Method m = RagOrchestrationService.class.getDeclaredMethod(
                "buildSystemPrompt", String.class, String.class, policyType, Department.class);
        m.setAccessible(true);

        String analytical = (String) m.invoke(
                service,
                "Compare operational risk and impact across systems",
                information,
                null,
                Department.ENTERPRISE);
        assertTrue(analytical.contains("Persona: careful investigator."));
        assertTrue(analytical.contains("Detected query type: analytical."));

        String historical = (String) m.invoke(
                service,
                "Show timeline changes between 2018 and 2022",
                information,
                null,
                Department.ENTERPRISE);
        assertTrue(historical.contains("Detected query type: historical."));

        String factual = (String) m.invoke(
                service,
                "What is operating pressure",
                information,
                null,
                Department.ENTERPRISE);
        assertTrue(factual.contains("Detected query type: factual."));
    }

    @Test
    void buildSystemPromptUsesCarefulInvestigatorForSummaryStyleQuery() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "quickLookupMaxTerms", 20);
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 40);

        String information = "[alpha.pdf]\nPage: 7\ncontent";
        Class<?> policyType = Class.forName("com.jreinhal.mercenary.service.RagOrchestrationService$ResponsePolicy");
        Method m = RagOrchestrationService.class.getDeclaredMethod(
                "buildSystemPrompt", String.class, String.class, policyType, Department.class);
        m.setAccessible(true);

        String prompt = (String) m.invoke(
                service,
                "Provide summary and compare impacts",
                information,
                null,
                Department.ENTERPRISE);
        assertTrue(prompt.contains("Persona: careful investigator."));
    }

    @Test
    void buildSystemPromptHandlesNullQuery() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "quickLookupMaxTerms", 9);
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 40);

        String information = "[alpha.pdf]\nPage: 1\ncontent";
        Class<?> policyType = Class.forName("com.jreinhal.mercenary.service.RagOrchestrationService$ResponsePolicy");
        Method m = RagOrchestrationService.class.getDeclaredMethod(
                "buildSystemPrompt", String.class, String.class, policyType, Department.class);
        m.setAccessible(true);

        String prompt = (String) m.invoke(service, null, information, null, Department.ENTERPRISE);
        assertTrue(prompt.contains("Persona: careful investigator."));
        assertTrue(prompt.contains("Detected query type: factual."));
    }

    @Test
    void enforceDocumentCeilingTruncatesWhenExceeded() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 2);

        Method m = RagOrchestrationService.class.getDeclaredMethod("enforceDocumentCeiling", List.class, String.class);
        m.setAccessible(true);

        List<Document> docs = List.of(
                new Document("a"),
                new Document("b"),
                new Document("c")
        );
        Object out = m.invoke(service, docs, "query");
        assertTrue(out instanceof List<?>);
        assertTrue(((List<?>) out).size() == 2);
    }

    @Test
    void enforceDocumentCeilingNoOpWhenDisabled() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 0);

        Method m = RagOrchestrationService.class.getDeclaredMethod("enforceDocumentCeiling", List.class, String.class);
        m.setAccessible(true);

        List<Document> docs = List.of(new Document("a"), new Document("b"));
        Object out = m.invoke(service, docs, "query");
        assertTrue(out instanceof List<?>);
        assertTrue(((List<?>) out).size() == 2);
    }

    @Test
    void enforceDocumentCeilingNoOpWhenAlreadyWithinLimit() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 5);

        Method m = RagOrchestrationService.class.getDeclaredMethod("enforceDocumentCeiling", List.class, String.class);
        m.setAccessible(true);

        List<Document> docs = List.of(new Document("a"), new Document("b"));
        Object out = m.invoke(service, docs, "query");
        assertTrue(out instanceof List<?>);
        assertTrue(((List<?>) out).size() == 2);
    }

    @Test
    void enforceDocumentCeilingHandlesNullAndEmptyDocs() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 5);

        Method m = RagOrchestrationService.class.getDeclaredMethod("enforceDocumentCeiling", List.class, String.class);
        m.setAccessible(true);

        Object nullOut = m.invoke(service, null, "query");
        assertTrue(nullOut == null);

        Object emptyOut = m.invoke(service, List.of(), "query");
        assertTrue(emptyOut instanceof List<?>);
        assertTrue(((List<?>) emptyOut).isEmpty());
    }

    @Test
    void selectTopDocumentsAppliesCeilingAndTopLimit() throws Exception {
        RagOrchestrationService service = newTestService();
        ReflectionTestUtils.setField(service, "documentsPerQueryCeiling", 20);

        Method m = RagOrchestrationService.class.getDeclaredMethod("selectTopDocuments", List.class, String.class);
        m.setAccessible(true);

        List<Document> docs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            docs.add(new Document("doc-" + i));
        }
        Object out = m.invoke(service, docs, "query");
        assertTrue(out instanceof List<?>);
        assertTrue(((List<?>) out).size() == 15);
    }

    @Test
    void extractiveResponsesAppendPageSuffixOutsideBracketCitations() throws Exception {
        Document doc = new Document("hello value is 42", Map.of("source", "file.pdf", "page_number", 3));

        Method extractive = RagOrchestrationService.class.getDeclaredMethod("buildExtractiveResponse", List.class, String.class);
        extractive.setAccessible(true);
        String response = (String) extractive.invoke(null, List.of(doc), "hello");

        assertNotNull(response);
        assertTrue(response.contains("[file.pdf] p. 3"));

        Method fallback = RagOrchestrationService.class.getDeclaredMethod("buildEvidenceFallbackResponse", List.class, String.class);
        fallback.setAccessible(true);
        String fb = (String) fallback.invoke(null, List.of(doc), "hello");

        assertNotNull(fb);
        assertTrue(fb.contains("[file.pdf] p. 3"));
    }

    private static RagOrchestrationService newTestService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        return new RagOrchestrationService(
                builder,
                mock(VectorStore.class),
                mock(AuditService.class),
                mock(QueryDecompositionService.class),
                mock(ReasoningTracer.class),
                mock(QuCoRagService.class),
                mock(AdaptiveRagService.class),
                mock(RewriteService.class),
                mock(RagPartService.class),
                mock(HybridRagService.class),
                mock(HiFiRagService.class),
                mock(MiARagService.class),
                mock(MegaRagService.class),
                mock(HGMemQueryEngine.class),
                mock(AgenticRagOrchestrator.class),
                mock(BidirectionalRagService.class),
                mock(ModalityRouter.class),
                mock(SectorConfig.class),
                mock(PromptGuardrailService.class),
                null,
                null,
                mock(LicenseService.class),
                mock(PiiRedactionService.class),
                mock(HipaaPolicy.class),
                null,
                Caffeine.newBuilder().maximumSize(10).build(),
                mock(WorkspaceQuotaService.class),
                "llama3.1:8b",
                0.0,
                256,
                4096
        );
    }
}

