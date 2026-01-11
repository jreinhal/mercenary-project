package com.jreinhal.mercenary.rag.ragpart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/**
 * Partition Assigner for RAGPart Defense.
 *
 * Assigns documents to partitions using consistent hashing based on document content.
 * This ensures:
 * 1. Same document always gets same partition (deterministic)
 * 2. Partitions are roughly balanced
 * 3. Assignment is based on content, not metadata (harder to game)
 */
@Component
public class PartitionAssigner {

    private static final Logger log = LoggerFactory.getLogger(PartitionAssigner.class);

    @Value("${sentinel.ragpart.partitions:4}")
    private int numPartitions;

    @Value("${sentinel.ragpart.hash-algorithm:SHA-256}")
    private String hashAlgorithm;

    /**
     * Assign a partition ID to a document.
     *
     * @param document The document to partition
     * @return Partition ID (0 to numPartitions-1)
     */
    public int assignPartition(Document document) {
        String content = document.getContent();
        if (content == null || content.isEmpty()) {
            // Fallback to random partition for empty documents
            return Math.abs(document.hashCode()) % numPartitions;
        }

        try {
            // Use cryptographic hash for uniform distribution
            MessageDigest digest = MessageDigest.getInstance(hashAlgorithm);
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));

            // Convert first 4 bytes to int for partition assignment
            int hashInt = ((hash[0] & 0xFF) << 24) |
                         ((hash[1] & 0xFF) << 16) |
                         ((hash[2] & 0xFF) << 8) |
                         (hash[3] & 0xFF);

            int partition = Math.abs(hashInt) % numPartitions;
            log.debug("Assigned partition {} to document", partition);
            return partition;

        } catch (NoSuchAlgorithmException e) {
            log.warn("Hash algorithm {} not available, using fallback", hashAlgorithm);
            return Math.abs(content.hashCode()) % numPartitions;
        }
    }

    /**
     * Assign partition and add to document metadata.
     *
     * @param document The document to partition
     * @return The document with partition_id added to metadata
     */
    public Document assignAndTag(Document document) {
        int partitionId = assignPartition(document);

        // Add partition to metadata
        Map<String, Object> metadata = document.getMetadata();
        metadata.put("partition_id", partitionId);

        log.debug("Tagged document with partition_id={}", partitionId);
        return document;
    }

    /**
     * Get the number of partitions.
     */
    public int getNumPartitions() {
        return numPartitions;
    }

    /**
     * Assign multiple documents to partitions.
     *
     * @param documents Documents to partition
     * @return Map of partition ID to document count (for diagnostics)
     */
    public Map<Integer, Long> assignBatch(Iterable<Document> documents) {
        java.util.Map<Integer, Long> distribution = new java.util.HashMap<>();

        for (Document doc : documents) {
            int partition = assignPartition(doc);
            doc.getMetadata().put("partition_id", partition);
            distribution.merge(partition, 1L, Long::sum);
        }

        log.info("Partition distribution: {}", distribution);
        return distribution;
    }

    /**
     * Validate that a document has been assigned a partition.
     */
    public boolean hasPartition(Document document) {
        return document.getMetadata().containsKey("partition_id");
    }

    /**
     * Get the partition ID of a document.
     *
     * @param document The document
     * @return Partition ID, or -1 if not assigned
     */
    public int getPartition(Document document) {
        Object partitionId = document.getMetadata().get("partition_id");
        if (partitionId instanceof Integer) {
            return (Integer) partitionId;
        } else if (partitionId instanceof Number) {
            return ((Number) partitionId).intValue();
        }
        return -1;
    }
}
