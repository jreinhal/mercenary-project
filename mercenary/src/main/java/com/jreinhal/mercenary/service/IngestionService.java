package com.jreinhal.mercenary.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private final VectorStore vectorStore;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    // UPDATE: Added 'fileName' parameter
    public void ingest(String text, String dept, String fileName) {

        // UPDATE: We now tag the document with BOTH 'dept' and 'source'
        Document rawDoc = new Document(text, Map.of(
                "dept", dept,
                "source", fileName
        ));

        var splitter = new TokenTextSplitter();
        List<Document> chunks = splitter.apply(List.of(rawDoc));

        vectorStore.add(chunks);
    }
}