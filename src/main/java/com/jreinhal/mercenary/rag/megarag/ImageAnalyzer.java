/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.ai.chat.client.ChatClient
 *  org.springframework.ai.chat.client.ChatClient$Builder
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Component
 */
package com.jreinhal.mercenary.rag.megarag;

import com.jreinhal.mercenary.rag.megarag.MegaRagService;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ImageAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(ImageAnalyzer.class);
    private final ChatClient chatClient;
    @Value(value="${sentinel.megarag.vision-model:llava}")
    private String visionModel;
    @Value(value="${sentinel.megarag.analysis-timeout-ms:30000}")
    private int analysisTimeoutMs;
    private static final String CLASSIFICATION_PROMPT = "Analyze this image and classify it into ONE of these categories:\n- CHART_BAR: Bar chart or bar graph\n- CHART_LINE: Line chart or time series\n- CHART_PIE: Pie chart or donut chart\n- DIAGRAM_FLOWCHART: Flowchart or process diagram\n- DIAGRAM_ARCHITECTURE: System architecture or technical diagram\n- DIAGRAM_ORG_CHART: Organizational chart\n- MAP: Geographic map or location diagram\n- PHOTO: Photograph of real-world scene\n- SCREENSHOT: Screenshot of software/UI\n- TABLE: Data table or spreadsheet\n- UNKNOWN: Cannot determine\n\nRespond with ONLY the category name, nothing else.\n";
    private static final String DESCRIPTION_PROMPT = "Provide a detailed description of this image for a RAG system.\nInclude:\n1. What type of visual this is\n2. Main subject/topic\n3. Key information shown\n4. Any text visible in the image\n5. For charts: trends, comparisons, data points\n6. For diagrams: components and relationships\n\nBe factual and specific. This description will be used for semantic search.\n";
    private static final String ENTITY_EXTRACTION_PROMPT = "Extract all named entities from this image. For each entity, provide:\n- Name: The entity name\n- Type: PERSON, ORG, LOCATION, DATE, METRIC, LABEL, or OTHER\n- Confidence: HIGH, MEDIUM, or LOW\n\nFormat each entity on a new line as: NAME|TYPE|CONFIDENCE\n\nIf no entities found, respond with: NONE\n";
    private static final String CHART_DATA_PROMPT = "This appears to be a chart. Extract the data shown:\n\n1. Chart title (if visible)\n2. X-axis label and values\n3. Y-axis label and values\n4. Data series with their values\n5. Any trends or notable patterns\n\nFormat as structured data that could be parsed.\n";

    public ImageAnalyzer(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    public MegaRagService.ImageAnalysis analyze(byte[] imageBytes, String filename) {
        log.info("MegaRAG ImageAnalyzer: Analyzing image '{}' ({} bytes)", filename, imageBytes.length);
        try {
            MegaRagService.ImageType imageType = this.classifyImage(imageBytes);
            log.debug("Classified as: {}", imageType);
            String description = this.generateDescription(imageBytes);
            String extractedText = this.extractText(imageBytes);
            List<MegaRagService.VisualEntity> entities = this.extractEntities(imageBytes);
            Map<String, Object> chartData = new HashMap<>();
            if (this.isChartType(imageType)) {
                chartData = this.extractChartData(imageBytes);
            }
            log.info("MegaRAG ImageAnalyzer: Completed analysis - type={}, entities={}, textLen={}", new Object[]{imageType, entities.size(), extractedText.length()});
            return new MegaRagService.ImageAnalysis(imageType, description, extractedText, entities, chartData);
        }
        catch (Exception e) {
            log.error("MegaRAG ImageAnalyzer: Analysis failed for '{}': {}", new Object[]{filename, e.getMessage(), e});
            return new MegaRagService.ImageAnalysis(MegaRagService.ImageType.UNKNOWN, "Image analysis failed: " + filename, "", List.of(), Map.of());
        }
    }

    private MegaRagService.ImageType classifyImage(byte[] imageBytes) {
        try {
            String response = this.callVisionModel(imageBytes, CLASSIFICATION_PROMPT);
            String classification = response.trim().toUpperCase().replace(" ", "_");
            try {
                return MegaRagService.ImageType.valueOf(classification);
            }
            catch (IllegalArgumentException e) {
                for (MegaRagService.ImageType type : MegaRagService.ImageType.values()) {
                    if (!classification.contains(type.name()) && !type.name().contains(classification)) continue;
                    return type;
                }
                return MegaRagService.ImageType.UNKNOWN;
            }
        }
        catch (Exception e) {
            log.warn("Image classification failed: {}", e.getMessage());
            return MegaRagService.ImageType.UNKNOWN;
        }
    }

    private String generateDescription(byte[] imageBytes) {
        try {
            return this.callVisionModel(imageBytes, DESCRIPTION_PROMPT);
        }
        catch (Exception e) {
            log.warn("Description generation failed: {}", e.getMessage());
            return "Visual content - analysis unavailable";
        }
    }

    private String extractText(byte[] imageBytes) {
        try {
            String prompt = "Extract ALL text visible in this image. Include labels, titles, captions, and any other readable text. Format as plain text.";
            return this.callVisionModel(imageBytes, prompt);
        }
        catch (Exception e) {
            log.warn("Text extraction failed: {}", e.getMessage());
            return "";
        }
    }

    private List<MegaRagService.VisualEntity> extractEntities(byte[] imageBytes) {
        try {
            String response = this.callVisionModel(imageBytes, ENTITY_EXTRACTION_PROMPT);
            if (response == null || response.isBlank() || response.trim().equals("NONE")) {
                return List.of();
            }
            ArrayList<MegaRagService.VisualEntity> entities = new ArrayList<MegaRagService.VisualEntity>();
            Pattern pattern = Pattern.compile("^(.+?)\\|(.+?)\\|(.+?)$", 8);
            Matcher matcher = pattern.matcher(response);
            while (matcher.find()) {
                String name = matcher.group(1).trim();
                String type = matcher.group(2).trim();
                String confidence = matcher.group(3).trim();
                double confScore = switch (confidence.toUpperCase()) {
                    case "HIGH" -> 0.9;
                    case "MEDIUM" -> 0.7;
                    case "LOW" -> 0.5;
                    default -> 0.6;
                };
                entities.add(new MegaRagService.VisualEntity(name, type, confScore, Map.of()));
            }
            return entities;
        }
        catch (Exception e) {
            log.warn("Entity extraction failed: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> extractChartData(byte[] imageBytes) {
        try {
            int idx;
            int endIdx;
            String response = this.callVisionModel(imageBytes, CHART_DATA_PROMPT);
            HashMap<String, Object> data = new HashMap<String, Object>();
            data.put("rawExtraction", response);
            if (response.toLowerCase().contains("title:") && (endIdx = response.indexOf("\n", idx = response.toLowerCase().indexOf("title:"))) > idx) {
                data.put("title", response.substring(idx + 6, endIdx).trim());
            }
            return data;
        }
        catch (Exception e) {
            log.warn("Chart data extraction failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private String callVisionModel(byte[] imageBytes, String prompt) {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String fullPrompt = prompt + "\n\n[Image data provided as base64]";
        try {
            String response = this.chatClient.prompt().user(u -> u.text(fullPrompt)).call().content();
            return response != null ? response : "";
        }
        catch (Exception e) {
            log.warn("Vision model call failed: {}", e.getMessage());
            throw e;
        }
    }

    private boolean isChartType(MegaRagService.ImageType type) {
        return type == MegaRagService.ImageType.CHART_BAR || type == MegaRagService.ImageType.CHART_LINE || type == MegaRagService.ImageType.CHART_PIE;
    }
}
