package com.jreinhal.mercenary.rag.megarag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.ChatOptionsBuilder;
import org.springframework.ai.chat.prompt.Prompt;

class ImageAnalyzerTest {

    @Test
    void analyzeAttachesImageMediaToVisionCalls() {
        CapturingVisionChatModel chatModel = new CapturingVisionChatModel("image/jpeg");
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ImageAnalyzer analyzer = new ImageAnalyzer(builder);

        byte[] fakeJpeg = new byte[]{
                (byte) 0xFF, (byte) 0xD8, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, (byte) 0xFF, (byte) 0xD9
        };

        MegaRagService.ImageAnalysis analysis = analyzer.analyze(fakeJpeg, "test.jpg");
        assertNotNull(analysis);

        assertTrue(chatModel.callCount > 0);
        assertEquals(chatModel.callCount, chatModel.callsWithMedia);
    }

    private static final class CapturingVisionChatModel implements ChatModel {
        private final String expectedMimeType;
        private int callCount = 0;
        private int callsWithMedia = 0;

        private CapturingVisionChatModel(String expectedMimeType) {
            this.expectedMimeType = expectedMimeType;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.callCount++;

            ChatOptions options = prompt.getOptions();
            assertNotNull(options);
            assertEquals("llava", options.getModel());

            boolean hasMedia = false;
            for (Message msg : prompt.getInstructions()) {
                if (msg instanceof UserMessage userMessage) {
                    hasMedia = userMessage.getMedia() != null && !userMessage.getMedia().isEmpty();
                    if (hasMedia) {
                        assertEquals(this.expectedMimeType, userMessage.getMedia().iterator().next().getMimeType().toString());
                    }
                    break;
                }
            }
            assertTrue(hasMedia, "Expected UserMessage with media attached");
            this.callsWithMedia++;

            String contents = prompt.getContents() != null ? prompt.getContents().toLowerCase() : "";
            String response;
            if (contents.contains("classify") && contents.contains("category")) {
                response = "TABLE";
            } else if (contents.contains("extract all named entities")) {
                response = "NONE";
            } else {
                response = "Stub";
            }

            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return ChatOptionsBuilder.builder().build();
        }
    }

    @Test
    void analyzeDetectsPngMimeType() {
        CapturingVisionChatModel chatModel = new CapturingVisionChatModel("image/png");
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ImageAnalyzer analyzer = new ImageAnalyzer(builder);

        byte[] fakePng = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03
        };

        MegaRagService.ImageAnalysis analysis = analyzer.analyze(fakePng, "test.png");
        assertNotNull(analysis);
        assertTrue(chatModel.callCount > 0);
        assertEquals(chatModel.callCount, chatModel.callsWithMedia);
    }

    @Test
    void analyzeDetectsGifMimeType() {
        CapturingVisionChatModel chatModel = new CapturingVisionChatModel("image/gif");
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ImageAnalyzer analyzer = new ImageAnalyzer(builder);

        byte[] fakeGif = new byte[]{
                0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x00, 0x00
        };

        MegaRagService.ImageAnalysis analysis = analyzer.analyze(fakeGif, "test.gif");
        assertNotNull(analysis);
        assertTrue(chatModel.callCount > 0);
        assertEquals(chatModel.callCount, chatModel.callsWithMedia);
    }

    @Test
    void analyzeDetectsWebpMimeType() {
        CapturingVisionChatModel chatModel = new CapturingVisionChatModel("image/webp");
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        ImageAnalyzer analyzer = new ImageAnalyzer(builder);

        byte[] fakeWebp = new byte[]{
                0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50
        };

        MegaRagService.ImageAnalysis analysis = analyzer.analyze(fakeWebp, "test.webp");
        assertNotNull(analysis);
        assertTrue(chatModel.callCount > 0);
        assertEquals(chatModel.callCount, chatModel.callsWithMedia);
    }
}
