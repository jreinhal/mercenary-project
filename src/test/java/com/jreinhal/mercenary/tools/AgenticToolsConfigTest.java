package com.jreinhal.mercenary.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jreinhal.mercenary.service.AgentToolService;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class AgenticToolsConfigTest {

    @Test
    void getDocumentInfoToolDelegatesToService() {
        AgentToolService service = mock(AgentToolService.class);
        when(service.getDocumentInfo("alpha.pdf", "ENTERPRISE"))
                .thenReturn("Document info:\n- source: alpha.pdf");
        AgenticToolsConfig cfg = new AgenticToolsConfig();

        Function<AgenticToolsConfig.DocumentInfoRequest, String> tool = cfg.getDocumentInfo(service);
        String out = tool.apply(new AgenticToolsConfig.DocumentInfoRequest("alpha.pdf", "ENTERPRISE"));

        assertThat(out).contains("Document info:");
        assertThat(out).contains("alpha.pdf");
    }

    @Test
    void getAdjacentChunksToolDelegatesToService() {
        AgentToolService service = mock(AgentToolService.class);
        when(service.getAdjacentChunks("alpha.pdf#2", 300, 400, "ENTERPRISE"))
                .thenReturn("Adjacent chunk context for alpha.pdf#2");
        AgenticToolsConfig cfg = new AgenticToolsConfig();

        Function<AgenticToolsConfig.AdjacentChunksRequest, String> tool = cfg.getAdjacentChunks(service);
        String out = tool.apply(new AgenticToolsConfig.AdjacentChunksRequest("alpha.pdf#2", 300, 400, "ENTERPRISE"));

        assertThat(out).contains("Adjacent chunk context");
        assertThat(out).contains("alpha.pdf#2");
    }

    @Test
    void getAdjacentChunksToolHandlesMissingChunkId() {
        AgentToolService service = mock(AgentToolService.class);
        AgenticToolsConfig cfg = new AgenticToolsConfig();

        Function<AgenticToolsConfig.AdjacentChunksRequest, String> tool = cfg.getAdjacentChunks(service);
        String out = tool.apply(new AgenticToolsConfig.AdjacentChunksRequest(" ", 300, 400, "ENTERPRISE"));

        assertThat(out).contains("No chunkId provided");
    }
}
