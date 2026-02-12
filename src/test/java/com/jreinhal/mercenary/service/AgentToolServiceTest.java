package com.jreinhal.mercenary.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

class AgentToolServiceTest {

    private MongoTemplate mongoTemplate;
    private AgentToolService service;

    @BeforeEach
    void setUp() {
        this.mongoTemplate = mock(MongoTemplate.class);
        this.service = new AgentToolService(this.mongoTemplate);
        WorkspaceContext.setCurrentWorkspaceId("workspace_default");
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void getDocumentInfoSummarizesMetadata() {
        List<Map> rows = List.of(
                Map.of(
                        "content", "chunk one",
                        "metadata", Map.of(
                                "source", "alpha.pdf",
                                "dept", "ENTERPRISE",
                                "workspaceId", "workspace_default",
                                "title", "Alpha Operations",
                                "docId", "ALPHA-2026",
                                "mimeType", "application/pdf",
                                "documentYear", 2026,
                                "documentDateEpoch", 1767225600000L,
                                "page_number", 1
                        )
                ),
                Map.of(
                        "content", "chunk two",
                        "metadata", Map.of(
                                "source", "alpha.pdf",
                                "dept", "ENTERPRISE",
                                "workspaceId", "workspace_default",
                                "page_number", 2
                        )
                )
        );
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(rows);

        String out = this.service.getDocumentInfo("alpha.pdf", "enterprise");

        assertThat(out).contains("Document info:");
        assertThat(out).contains("- source: alpha.pdf");
        assertThat(out).contains("- docId: ALPHA-2026");
        assertThat(out).contains("- title: Alpha Operations");
        assertThat(out).contains("- department: ENTERPRISE");
        assertThat(out).contains("- chunkCount: 2");
        assertThat(out).contains("- pageCountEstimate: 2");
        verify(this.mongoTemplate).find(any(Query.class), eq(Map.class), eq("vector_store"));
    }

    @Test
    void getDocumentInfoValidatesBlankId() {
        String out = this.service.getDocumentInfo(" ", "ENTERPRISE");

        assertThat(out).contains("No documentId provided");
        verifyNoInteractions(this.mongoTemplate);
    }

    @Test
    void getDocumentInfoReturnsNotFoundWhenNoRows() {
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(List.of());

        String out = this.service.getDocumentInfo("missing.pdf", "ENTERPRISE");

        assertThat(out).contains("No document metadata found");
    }

    @Test
    void getDocumentInfoHandlesInvalidTemporalMetadataAndBlankDepartment() {
        List<Map> rows = List.of(
                Map.of(
                        "content", "chunk one",
                        "metadata", Map.of(
                                "source", "",
                                "workspaceId", "workspace_default",
                                "documentYear", "not-a-year",
                                "documentDateEpoch", "not-an-epoch"
                        )
                )
        );
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(rows);

        String out = this.service.getDocumentInfo("folder/alpha.pdf", " ");

        assertThat(out).contains("- source: folder/alpha.pdf");
        assertThat(out).doesNotContain("documentYear:");
        assertThat(out).doesNotContain("department:");
    }

    @Test
    void getAdjacentChunksReturnsBeforeAndAfterContext() {
        List<Map> rows = List.of(
                chunkRow("alpha.pdf", 0, "intro context"),
                chunkRow("alpha.pdf", 1, "pressure baseline"),
                chunkRow("alpha.pdf", 2, "target measurement 42 PSI"),
                chunkRow("alpha.pdf", 3, "follow-up mitigation"),
                chunkRow("alpha.pdf", 4, "appendix")
        );
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(rows);

        String out = this.service.getAdjacentChunks("alpha.pdf#2", 200, 200, "ENTERPRISE");

        assertThat(out).contains("Adjacent chunk context for alpha.pdf#2:");
        assertThat(out).contains("BEFORE:");
        assertThat(out).contains("[alpha.pdf#1]");
        assertThat(out).contains("TARGET:");
        assertThat(out).contains("[alpha.pdf#2]");
        assertThat(out).contains("AFTER:");
        assertThat(out).contains("[alpha.pdf#3]");
    }

    @Test
    void getAdjacentChunksReturnsNotFoundWhenSourceHasNoChunks() {
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(List.of());

        String out = this.service.getAdjacentChunks("alpha.pdf#2", null, null, "ENTERPRISE");

        assertThat(out).contains("No chunks found for source");
    }

    @Test
    void getAdjacentChunksReturnsNotFoundWhenAnchorMissing() {
        List<Map> rows = List.of(
                chunkRow("alpha.pdf", 0, "intro"),
                chunkRow("alpha.pdf", 1, "body")
        );
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(rows);

        String out = this.service.getAdjacentChunks("alpha.pdf#9", -10, 99999, "ENTERPRISE");

        assertThat(out).contains("was not found");
    }

    @Test
    void getAdjacentChunksRejectsNegativeChunkIndex() {
        String out = this.service.getAdjacentChunks("alpha.pdf#-1", 100, 100, "ENTERPRISE");

        assertThat(out).contains("Invalid chunkId format");
        verifyNoInteractions(this.mongoTemplate);
    }

    @Test
    void getAdjacentChunksRejectsNonNumericChunkIndex() {
        String out = this.service.getAdjacentChunks("alpha.pdf#abc", 100, 100, "ENTERPRISE");

        assertThat(out).contains("Invalid chunkId format");
        verifyNoInteractions(this.mongoTemplate);
    }

    @Test
    void getAdjacentChunksTruncatesLongChunkContent() {
        String longContent = "x".repeat(2000);
        List<Map> rows = List.of(
                chunkRow("alpha.pdf", 2, longContent)
        );
        when(this.mongoTemplate.find(any(Query.class), eq(Map.class), eq("vector_store"))).thenReturn(rows);

        String out = this.service.getAdjacentChunks("alpha.pdf#2", 100, 100, "ENTERPRISE");

        assertThat(out).contains("TARGET:");
        assertThat(out).contains("...");
    }

    @Test
    void getAdjacentChunksRejectsInvalidChunkId() {
        String out = this.service.getAdjacentChunks("alpha.pdf", 100, 100, "ENTERPRISE");

        assertThat(out).contains("Invalid chunkId format");
        verifyNoInteractions(this.mongoTemplate);
    }

    private static Map<String, Object> chunkRow(String source, int chunkIndex, String content) {
        return Map.of(
                "content", content,
                "metadata", Map.of(
                        "source", source,
                        "dept", "ENTERPRISE",
                        "workspaceId", "workspace_default",
                        "chunk_index", chunkIndex
                )
        );
    }
}
