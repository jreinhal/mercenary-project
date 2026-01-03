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
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // --- NEW: The method the Controller is looking for ---
    public void ingestFile(InputStream inputStream, String dept, String fileName) {
        try {
            log.info("Starting ingestion for file: {}", fileName);

            // 1. Setup Tika to parse the stream
            AutoDetectParser parser = new AutoDetectParser();
            BodyContentHandler handler = new BodyContentHandler(-1); // -1 removes size limit
            Metadata metadata = new Metadata();

            // 2. Parse (Extract text from PDF/Word/Excel)
            parser.parse(inputStream, handler, metadata, new ParseContext());
            String extractedText = handler.toString();

            log.info("Extracted {} characters from {}", extractedText.length(), fileName);

            // 3. Create the AI Document with Metadata
            Document doc = new Document(extractedText, Map.of(
                    "dept", dept,
                    "source", fileName
            ));

            // 4. Save to Vector Database
            vectorStore.add(List.of(doc));
            log.info("Successfully saved {} to Vector Store.", fileName);

        } catch (Exception e) {
            log.error("Failed to ingest file: {}", fileName, e);
            throw new RuntimeException("Ingestion failed for " + fileName, e);
        }
    }
}