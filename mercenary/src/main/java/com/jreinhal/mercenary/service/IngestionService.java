package com.jreinhal.mercenary.service;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    public void ingestFile(InputStream inputStream, String dept, String fileName) {
        try {
            log.info("Starting ingestion for file: {}", fileName);

            // 1. Setup Tika
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1); // -1 removes size limit
            Metadata tikaMetadata = new Metadata();

            // 2. Parse (Extract text only)
            parser.parse(inputStream, handler, tikaMetadata, new ParseContext());
            String extractedText = handler.toString();

            log.info("Extracted {} characters from {}", extractedText.length(), fileName);

            // 3. STRICT METADATA (The Fix)
            // We ignore everything Tika found. We only save what we verify.
            // This guarantees NO 'Charset' objects or junk data enter the database.
            Map<String, Object> safeMetadata = new HashMap<>();
            safeMetadata.put("dept", dept);
            safeMetadata.put("source", fileName);

            // 4. Create the Document
            Document doc = new Document(extractedText, safeMetadata);

            // 5. Save to Vector Store
            vectorStore.add(List.of(doc));
            log.info("Successfully saved {} to Vector Store.", fileName);

        } catch (Exception e) {
            log.error("Failed to ingest file: {}", fileName, e);
            throw new RuntimeException("Ingestion failed for " + fileName, e);
        }
    }
}