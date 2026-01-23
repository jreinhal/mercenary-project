package com.jreinhal.mercenary.rag.ragpart;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PartitionAssigner {
    private static final Logger log = LoggerFactory.getLogger(PartitionAssigner.class);
    @Value(value="${sentinel.ragpart.partitions:4}")
    private int numPartitions;
    @Value(value="${sentinel.ragpart.hash-algorithm:SHA-256}")
    private String hashAlgorithm;

    public int assignPartition(Document document) {
        String content = document.getContent();
        if (content == null || content.isEmpty()) {
            return Math.abs(document.hashCode()) % this.numPartitions;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance(this.hashAlgorithm);
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            int hashInt = (hash[0] & 0xFF) << 24 | (hash[1] & 0xFF) << 16 | (hash[2] & 0xFF) << 8 | hash[3] & 0xFF;
            int partition = Math.abs(hashInt) % this.numPartitions;
            log.debug("Assigned partition {} to document", partition);
            return partition;
        }
        catch (NoSuchAlgorithmException e) {
            log.warn("Hash algorithm {} not available, using fallback", this.hashAlgorithm);
            return Math.abs(content.hashCode()) % this.numPartitions;
        }
    }

    public Document assignAndTag(Document document) {
        int partitionId = this.assignPartition(document);
        Map<String, Object> metadata = document.getMetadata();
        metadata.put("partition_id", partitionId);
        log.debug("Tagged document with partition_id={}", partitionId);
        return document;
    }

    public int getNumPartitions() {
        return this.numPartitions;
    }

    public Map<Integer, Long> assignBatch(Iterable<Document> documents) {
        HashMap<Integer, Long> distribution = new HashMap<Integer, Long>();
        for (Document doc : documents) {
            int partition = this.assignPartition(doc);
            doc.getMetadata().put("partition_id", partition);
            distribution.merge(partition, 1L, Long::sum);
        }
        log.info("Partition distribution: {}", distribution);
        return distribution;
    }

    public boolean hasPartition(Document document) {
        return document.getMetadata().containsKey("partition_id");
    }

    public int getPartition(Document document) {
        Object partitionId = document.getMetadata().get("partition_id");
        if (partitionId instanceof Integer) {
            return (Integer)partitionId;
        }
        if (partitionId instanceof Number) {
            return ((Number)partitionId).intValue();
        }
        return -1;
    }
}
