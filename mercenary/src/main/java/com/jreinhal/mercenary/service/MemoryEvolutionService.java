package com.jreinhal.mercenary.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jreinhal.mercenary.constant.MetadataConstants;

import org.springframework.beans.factory.annotation.Value;

@Service
public class MemoryEvolutionService {

    private static final Logger log = LoggerFactory.getLogger(MemoryEvolutionService.class);
    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    @Value("${mercenary.memory.similarity-threshold:0.82}")
    private double similarityThreshold;

    public MemoryEvolutionService(VectorStore vectorStore, ChatClient.Builder chatClientBuilder) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClientBuilder.build();
    }

    public Document evolve(Document newDoc) {
        try {
            // 1. Search for existing ACTIVE documents that are similar
            // NOTE: SimpleVectorStore does NOT support metadata filtering in this version.
            // We must perform the search WITHOUT the filter, and then filter in memory (Java).
            
            List<Document> similarDocs = vectorStore.similaritySearch(
                    SearchRequest.query(newDoc.getContent())
                            .withTopK(5) // Fetch more to allow for post-filtering
                            .withSimilarityThreshold(similarityThreshold));

            // 2. In-Memory Filtering (Manual Check for Status != ARCHIVED)
            Document oldDoc = null;
            for (Document doc : similarDocs) {
                Object status = doc.getMetadata().get(MetadataConstants.STATUS_KEY);
                // If status is NOT archived, we consider it a candidate
                if (!MetadataConstants.STATUS_ARCHIVED.equals(status)) {
                    oldDoc = doc;
                    break; // Found the best match that is active
                }
            }

            if (oldDoc == null) {
                // No match found, returns new doc marked as ACTIVE
                return tagAsActive(newDoc);
            }

            log.info("MEMORY EVOLUTION: Found similar memory (ID: {}). Synthesizing...", oldDoc.getId());

            // 3. Synthesize Evolved Content
            String mergedContent = chatClient.prompt()
                    .system("You are the memory core of an intelligence maven. Merge the old and new information into a single, dense, accurate fact. Do not lose details.")
                    .user("OLD MEMORY: " + oldDoc.getContent() + "\n\nNEW MEMORY: " + newDoc.getContent())
                    .call()
                    .content();

            // 4. Archive the Old Document (Soft Delete)
            archiveDocument(oldDoc);

            // 5. Return the new Evolved Document
            // We inherit metadata from the new doc, but we could mix in old metadata if
            // needed.
            // For now, new doc metadata takes precedence + ACTIVE status.
            Document evolvedDoc = new Document(mergedContent, new HashMap<>(newDoc.getMetadata()));
            evolvedDoc.getMetadata().put(MetadataConstants.STATUS_KEY, MetadataConstants.STATUS_ACTIVE);
            evolvedDoc.getMetadata().put(MetadataConstants.EVOLUTION_NOTE_KEY, "Evolved from " + oldDoc.getId());

            log.info("MEMORY EVOLUTION: Success. Old memory archived. New memory evolved.");
            return evolvedDoc;

        } catch (Exception e) {
            log.error("Memory Evolution failed. Falling back to standard ingestion.", e);
            return tagAsActive(newDoc);
        }
    }

    private Document tagAsActive(Document doc) {
        doc.getMetadata().put(MetadataConstants.STATUS_KEY, MetadataConstants.STATUS_ACTIVE);
        return doc;
    }

    private void archiveDocument(Document doc) {
        // To update metadata, we technically need to delete and re-insert in many
        // vector stores,
        // (depending on the implementation). Safe bet is remove + add.
        // NOTE: standard Spring AI VectorStore might not support delete(id) easily
        // depending on the impl.
        // But usually .delete(List<String> ids) works.
        try {
            if (doc.getId() != null) {
                // Remove old
                vectorStore.delete(List.of(doc.getId()));

                // Re-add with ARCHIVED status
                Map<String, Object> archivedMeta = new HashMap<>(doc.getMetadata());
                archivedMeta.put(MetadataConstants.STATUS_KEY, MetadataConstants.STATUS_ARCHIVED);
                Document archivedDoc = new Document(doc.getId(), doc.getContent(), archivedMeta);

                vectorStore.add(List.of(archivedDoc));
            }
        } catch (Exception e) {
            log.warn("Failed to archive document {}. It might stay active.", doc.getId(), e);
        }
    }
}
