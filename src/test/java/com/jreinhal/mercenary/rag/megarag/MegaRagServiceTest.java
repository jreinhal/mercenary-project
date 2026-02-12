package com.jreinhal.mercenary.rag.megarag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.reasoning.ReasoningTracer;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class MegaRagServiceTest {

    private VectorStore vectorStore;
    private MongoTemplate mongoTemplate;
    private ChatClient.Builder chatClientBuilder;
    private ChatClient chatClient;
    private ImageAnalyzer imageAnalyzer;
    private VisualEntityLinker visualEntityLinker;
    private ReasoningTracer reasoningTracer;
    private ExecutorService executorService;
    private MegaRagService service;

    @BeforeEach
    void setUp() {
        this.vectorStore = mock(VectorStore.class);
        this.mongoTemplate = mock(MongoTemplate.class);
        this.chatClientBuilder = mock(ChatClient.Builder.class);
        this.chatClient = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(this.chatClientBuilder.build()).thenReturn(this.chatClient);
        this.imageAnalyzer = mock(ImageAnalyzer.class);
        this.visualEntityLinker = mock(VisualEntityLinker.class);
        this.reasoningTracer = mock(ReasoningTracer.class);
        this.executorService = Executors.newSingleThreadExecutor();
        this.service = new MegaRagService(
                this.vectorStore,
                this.mongoTemplate,
                this.chatClientBuilder,
                this.imageAnalyzer,
                this.visualEntityLinker,
                this.reasoningTracer,
                this.executorService);
        ReflectionTestUtils.setField(this.service, "enabled", true);
        ReflectionTestUtils.setField(this.service, "multimodalEmbeddingsEnabled", true);
        ReflectionTestUtils.setField(this.service, "multimodalQueryPrefix", "vision query:");
        ReflectionTestUtils.setField(this.service, "multimodalContextMaxChars", 200);
        ReflectionTestUtils.setField(this.service, "futureTimeoutSeconds", 2);
        this.service.init();
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
        if (this.executorService != null) {
            this.executorService.shutdownNow();
        }
    }

    @Test
    void retrieveUsesPrefixedVisualQueryWhenMultimodalEmbeddingsAreEnabled() {
        when(this.vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        WorkspaceContext.setCurrentWorkspaceId("ws-test");

        this.service.retrieve("pump anomaly", "ENTERPRISE");

        ArgumentCaptor<SearchRequest> requestCaptor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(this.vectorStore, times(2)).similaritySearch(requestCaptor.capture());
        List<SearchRequest> requests = requestCaptor.getAllValues();
        assertEquals(2, requests.size());

        SearchRequest visualRequest = requests.stream()
                .filter(r -> r.getQuery() != null && r.getQuery().startsWith("vision query:"))
                .findFirst()
                .orElseThrow();
        SearchRequest textRequest = requests.stream()
                .filter(r -> r.getQuery() != null && !r.getQuery().startsWith("vision query:"))
                .findFirst()
                .orElseThrow();

        assertEquals("pump anomaly", textRequest.getQuery());
        assertEquals("vision query: pump anomaly", visualRequest.getQuery());
    }

    @Test
    void ingestVisualAssetAddsJointEmbeddingTextAndImageMedia() {
        WorkspaceContext.setCurrentWorkspaceId("ws-test");
        byte[] image = new byte[]{1, 2, 3, 4};
        MegaRagService.VisualEntity entity = new MegaRagService.VisualEntity("thrust", "metric", 0.91, Map.of());
        MegaRagService.ImageAnalysis analysis = new MegaRagService.ImageAnalysis(
                MegaRagService.ImageType.CHART_LINE,
                "line chart description",
                "Q1=42 Q2=45",
                List.of(entity),
                Map.of());

        when(this.imageAnalyzer.analyze(any(byte[].class), anyString())).thenReturn(analysis);
        when(this.visualEntityLinker.linkEntities(anyList(), anyString(), anyString(), anyString())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        doNothing().when(this.vectorStore).add(docsCaptor.capture());

        MegaRagService.VisualIngestionResult result = this.service.ingestVisualAsset(
                image,
                "chart.png",
                "ENTERPRISE",
                "related engineering context");

        assertTrue(result.success());
        assertFalse(docsCaptor.getValue().isEmpty());
        Document indexed = docsCaptor.getValue().get(0);
        assertEquals("line chart description", indexed.getContent());
        assertEquals(1, indexed.getMedia().size());
        assertEquals("line chart description", indexed.getMetadata().get("visualDescription"));
        assertTrue(indexed.getMetadata().containsKey("embeddingText"));
        String embeddingText = String.valueOf(indexed.getMetadata().get("embeddingText"));
        assertTrue(embeddingText.contains("image_type: CHART_LINE"));
        assertTrue(embeddingText.contains("description: line chart description"));
        assertTrue(embeddingText.contains("ocr_text: Q1=42 Q2=45"));
        assertTrue(embeddingText.contains("entities: thrust"));
        assertTrue(embeddingText.contains("related_context: related engineering context"));
    }

    @Test
    void ingestVisualAssetSkipsContextWhenConfiguredMaxCharsIsNegative() {
        WorkspaceContext.setCurrentWorkspaceId("ws-test");
        ReflectionTestUtils.setField(this.service, "multimodalContextMaxChars", -10);

        MegaRagService.ImageAnalysis analysis = new MegaRagService.ImageAnalysis(
                MegaRagService.ImageType.CHART_BAR,
                "bar chart",
                "A=10 B=12",
                List.of(),
                Map.of());
        when(this.imageAnalyzer.analyze(any(byte[].class), anyString())).thenReturn(analysis);
        when(this.visualEntityLinker.linkEntities(anyList(), anyString(), anyString(), anyString())).thenReturn(List.of());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> docsCaptor = ArgumentCaptor.forClass(List.class);
        doNothing().when(this.vectorStore).add(docsCaptor.capture());

        MegaRagService.VisualIngestionResult result = this.service.ingestVisualAsset(
                new byte[]{5, 6, 7},
                "chart.jpg",
                "ENTERPRISE",
                "context that should be skipped");

        assertTrue(result.success());
        String embeddingText = String.valueOf(docsCaptor.getValue().get(0).getMetadata().get("embeddingText"));
        assertFalse(embeddingText.contains("related_context:"));
    }
}
