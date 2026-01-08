package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.model.MemoryPoint;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

@Service
public class SecureIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SecureIngestionService.class);
    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;

    public SecureIngestionService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
    }

    public void ingestFile(InputStream inputStream, String dept, String fileName) {
        try {
            log.info("Initiating RAGPart Defense Protocol for: {}", fileName);

            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1);
            parser.parse(inputStream, handler, new Metadata(), new ParseContext());
            String rawText = handler.toString();

            // 1. Extract Entities (The Nodes)
            String entityJson = chatClient.prompt()
                    .system("Extract the top 5 key entities (People, Projects, Locations) from the text. Output as comma-separated list.")
                    .user(rawText.substring(0, Math.min(rawText.length(), 3000)))
                    .call()
                    .content();
            List<String> entities = Arrays.asList(entityJson.split(","));

            // 2. Fragment & Embed
            List<String> fragments = splitIntoFragments(rawText, 500);
            int k = 3; // Window size
            List<org.springframework.ai.document.Document> docsToSave = new ArrayList<>();

            // === FALLBACK FOR SMALL FILES ===
            if (fragments.size() < k) {
                log.info("File too short for sliding window. Processing as single robust block.");
                List<Double> embeddingList = embeddingModel.embed(rawText);
                Map<String, Object> meta = new HashMap<>();
                meta.put("dept", dept);
                meta.put("source", fileName);
                meta.put("entities", entities);
                MemoryPoint point = new MemoryPoint(rawText, entities, embeddingList, meta);
                docsToSave.add(point.toDocument());
            }
            // === RAGPART SLIDING WINDOW ===
            else {
                for (int i = 0; i < fragments.size() - k + 1; i++) {
                    List<String> window = fragments.subList(i, i + k);
                    String combinedText = String.join(" ", window);

                    RealVector sumVector = null;
                    for (String frag : window) {
                        List<Double> embeddingList = embeddingModel.embed(frag);
                        // Manual conversion to double[] for math operations
                        double[] embeddingArray = embeddingList.stream().mapToDouble(Double::doubleValue).toArray();
                        RealVector v = new ArrayRealVector(embeddingArray);

                        if (sumVector == null) sumVector = v;
                        else sumVector = sumVector.add(v);
                    }

                    if (sumVector != null) {
                        RealVector avgVector = sumVector.mapDivide(k);
                        List<Double> robustEmbedding = new ArrayList<>();
                        for (double d : avgVector.toArray()) {
                            robustEmbedding.add(d);
                        }

                        Map<String, Object> meta = new HashMap<>();
                        meta.put("dept", dept);
                        meta.put("source", fileName);
                        meta.put("entities", entities);

                        MemoryPoint point = new MemoryPoint(combinedText, entities, robustEmbedding, meta);
                        docsToSave.add(point.toDocument());
                    }
                }
            }

            vectorStore.add(docsToSave);
            log.info("Securely ingested {} memory points.", docsToSave.size());

        } catch (Exception e) {
            log.error("Ingestion failed", e);
            throw new RuntimeException(e);
        }
    }

    private List<String> splitIntoFragments(String text, int size) {
        List<String> fragments = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            fragments.add(text.substring(i, Math.min(text.length(), i + size)));
        }
        return fragments;
    }
}