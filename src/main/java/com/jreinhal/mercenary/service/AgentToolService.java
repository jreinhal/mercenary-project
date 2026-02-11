package com.jreinhal.mercenary.service;

import com.jreinhal.mercenary.workspace.WorkspaceContext;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

@Service
public class AgentToolService {
    private static final Logger log = LoggerFactory.getLogger(AgentToolService.class);
    private static final String VECTOR_STORE_COLLECTION = "vector_store";
    private static final int DEFAULT_CONTEXT_TOKENS = 350;
    private static final int MAX_CONTEXT_TOKENS = 2000;
    private static final int MAX_CHUNK_CHARS = 1200;
    private static final int MAX_TOTAL_CHARS = 12000;
    private static final List<String> DOC_ID_META_KEYS = List.of(
            "doc_id", "docid", "docId", "document_id", "documentId", "id");
    private static final List<String> TITLE_META_KEYS = List.of(
            "title", "document_title", "documentTitle", "doc_title", "name");

    private final MongoTemplate mongoTemplate;

    public AgentToolService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public String getDocumentInfo(String documentId, String department) {
        if (documentId == null || documentId.isBlank()) {
            return "No documentId provided.";
        }
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String normalizedDept = normalizeDepartment(department);
        Set<String> idVariants = buildIdVariants(documentId);

        Query query = new Query();
        query.addCriteria(Criteria.where("metadata.workspaceId").is(workspaceId));
        if (!normalizedDept.isBlank()) {
            query.addCriteria(Criteria.where("metadata.dept").is(normalizedDept));
        }
        query.addCriteria(new Criteria().orOperator(buildDocumentLookupCriteria(idVariants).toArray(new Criteria[0])));
        query.limit(3000);

        List<Map> rows = this.mongoTemplate.find(query, Map.class, VECTOR_STORE_COLLECTION);
        if (rows == null || rows.isEmpty()) {
            return "No document metadata found for '" + documentId.trim() + "' in the current workspace.";
        }

        String source = "";
        String title = "";
        String docId = "";
        String mimeType = "";
        Integer documentYear = null;
        Long documentDateEpoch = null;
        TreeSet<Integer> pages = new TreeSet<>();

        for (Map row : rows) {
            Map<String, Object> meta = metadataOf(row);
            if (meta.isEmpty()) {
                continue;
            }
            source = firstNonBlank(source, meta.get("source"), meta.get("filename"));
            docId = firstNonBlank(docId, findMetadataValue(meta, DOC_ID_META_KEYS));
            title = firstNonBlank(title, findMetadataValue(meta, TITLE_META_KEYS));
            mimeType = firstNonBlank(mimeType, meta.get("mimeType"));
            if (documentYear == null) {
                documentYear = parseInteger(meta.get("documentYear"));
            }
            if (documentDateEpoch == null) {
                documentDateEpoch = parseLong(meta.get("documentDateEpoch"));
            }
            Integer page = parseInteger(meta.get("page_number"));
            if (page != null && page > 0) {
                pages.add(page);
            }
            Integer endPage = parseInteger(meta.get("end_page_number"));
            if (endPage != null && endPage > 0) {
                pages.add(endPage);
            }
        }

        if (source.isBlank()) {
            source = documentId.trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Document info:\n");
        sb.append("- source: ").append(source).append("\n");
        if (!docId.isBlank()) {
            sb.append("- docId: ").append(docId).append("\n");
        }
        if (!title.isBlank()) {
            sb.append("- title: ").append(title).append("\n");
        }
        if (!normalizedDept.isBlank()) {
            sb.append("- department: ").append(normalizedDept).append("\n");
        }
        sb.append("- workspaceId: ").append(workspaceId).append("\n");
        if (!mimeType.isBlank()) {
            sb.append("- mimeType: ").append(mimeType).append("\n");
        }
        if (documentYear != null) {
            sb.append("- documentYear: ").append(documentYear).append("\n");
        }
        if (documentDateEpoch != null) {
            sb.append("- documentDateEpoch: ").append(documentDateEpoch).append("\n");
        }
        sb.append("- chunkCount: ").append(rows.size()).append("\n");
        if (!pages.isEmpty()) {
            sb.append("- pageCountEstimate: ").append(pages.last()).append("\n");
        }
        log.debug("AgentTool getDocumentInfo resolved {} rows for {}", rows.size(), source);
        return sb.toString().trim();
    }

    public String getAdjacentChunks(String chunkId, Integer beforeTokens, Integer afterTokens, String department) {
        ChunkReference ref = parseChunkReference(chunkId);
        if (ref == null) {
            return "Invalid chunkId format. Expected 'source#chunk_index'.";
        }
        int beforeBudget = sanitizeTokenBudget(beforeTokens);
        int afterBudget = sanitizeTokenBudget(afterTokens);
        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String normalizedDept = normalizeDepartment(department);

        Query query = new Query();
        query.addCriteria(Criteria.where("metadata.workspaceId").is(workspaceId));
        if (!normalizedDept.isBlank()) {
            query.addCriteria(Criteria.where("metadata.dept").is(normalizedDept));
        }
        query.addCriteria(new Criteria().orOperator(
                Criteria.where("metadata.source").is(ref.source()),
                Criteria.where("metadata.filename").is(ref.source())));
        query.addCriteria(Criteria.where("metadata.chunk_index").exists(true));
        query.with(Sort.by(Sort.Direction.ASC, "metadata.chunk_index"));
        query.limit(4000);

        List<Map> rows = this.mongoTemplate.find(query, Map.class, VECTOR_STORE_COLLECTION);
        if (rows == null || rows.isEmpty()) {
            return "No chunks found for source '" + ref.source() + "' in the current workspace.";
        }

        List<ChunkRow> chunks = new ArrayList<>();
        for (Map row : rows) {
            Map<String, Object> meta = metadataOf(row);
            Integer index = parseInteger(meta.get("chunk_index"));
            if (index == null) {
                continue;
            }
            String source = firstNonBlank("", meta.get("source"), meta.get("filename"));
            String content = row != null && row.get("content") != null ? String.valueOf(row.get("content")) : "";
            chunks.add(new ChunkRow(index, source.isBlank() ? ref.source() : source, content));
        }
        chunks.sort((a, b) -> Integer.compare(a.chunkIndex(), b.chunkIndex()));

        int anchorPos = -1;
        for (int i = 0; i < chunks.size(); i++) {
            if (chunks.get(i).chunkIndex() == ref.chunkIndex()) {
                anchorPos = i;
                break;
            }
        }
        if (anchorPos < 0) {
            return "Chunk '" + ref.source() + "#" + ref.chunkIndex() + "' was not found.";
        }

        ChunkRow target = chunks.get(anchorPos);
        List<ChunkRow> before = new ArrayList<>();
        List<ChunkRow> after = new ArrayList<>();

        int beforeUsed = 0;
        for (int i = anchorPos - 1; i >= 0; i--) {
            ChunkRow candidate = chunks.get(i);
            int est = estimateTokens(candidate.content());
            if (!before.isEmpty() && beforeUsed + est > beforeBudget) {
                break;
            }
            before.add(0, candidate);
            beforeUsed += est;
            if (beforeUsed >= beforeBudget) {
                break;
            }
        }

        int afterUsed = 0;
        for (int i = anchorPos + 1; i < chunks.size(); i++) {
            ChunkRow candidate = chunks.get(i);
            int est = estimateTokens(candidate.content());
            if (!after.isEmpty() && afterUsed + est > afterBudget) {
                break;
            }
            after.add(candidate);
            afterUsed += est;
            if (afterUsed >= afterBudget) {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Adjacent chunk context for ").append(ref.source()).append("#").append(ref.chunkIndex()).append(":\n");
        sb.append("- workspaceId: ").append(workspaceId).append("\n");
        if (!normalizedDept.isBlank()) {
            sb.append("- department: ").append(normalizedDept).append("\n");
        }
        sb.append("- beforeTokensUsed: ").append(beforeUsed).append(" / ").append(beforeBudget).append("\n");
        sb.append("- afterTokensUsed: ").append(afterUsed).append(" / ").append(afterBudget).append("\n");

        if (!before.isEmpty()) {
            sb.append("\nBEFORE:\n");
            appendChunks(sb, before);
        }
        sb.append("\nTARGET:\n");
        appendChunk(sb, target);
        if (!after.isEmpty()) {
            sb.append("\nAFTER:\n");
            appendChunks(sb, after);
        }

        String result = sb.toString().trim();
        if (result.length() > MAX_TOTAL_CHARS) {
            return result.substring(0, MAX_TOTAL_CHARS - 3) + "...";
        }
        return result;
    }

    private static List<Criteria> buildDocumentLookupCriteria(Set<String> idVariants) {
        ArrayList<Criteria> criteria = new ArrayList<>();
        for (String id : idVariants) {
            criteria.add(Criteria.where("metadata.source").is(id));
            criteria.add(Criteria.where("metadata.filename").is(id));
            criteria.add(Criteria.where("metadata.docId").is(id));
            criteria.add(Criteria.where("metadata.documentId").is(id));
            criteria.add(Criteria.where("metadata.doc_id").is(id));
        }
        return criteria;
    }

    private static Set<String> buildIdVariants(String id) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (id == null) {
            return variants;
        }
        String trimmed = id.trim();
        if (trimmed.isBlank()) {
            return variants;
        }
        variants.add(trimmed);
        String normalizedPath = trimmed.replace('\\', '/');
        variants.add(normalizedPath);
        int slash = normalizedPath.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < normalizedPath.length()) {
            variants.add(normalizedPath.substring(slash + 1));
        }
        return variants;
    }

    private static String normalizeDepartment(String department) {
        if (department == null || department.isBlank()) {
            return "";
        }
        return department.trim().toUpperCase(Locale.ROOT);
    }

    private static Map<String, Object> metadataOf(Map row) {
        if (row == null) {
            return Map.of();
        }
        Object meta = row.get("metadata");
        if (!(meta instanceof Map<?, ?> rawMeta)) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, Object> normalized = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMeta.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            normalized.put(entry.getKey().toString(), entry.getValue());
        }
        return normalized;
    }

    private static String firstNonBlank(String existing, Object... values) {
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            String candidate = String.valueOf(value).trim();
            if (!candidate.isBlank()) {
                return candidate;
            }
        }
        return existing == null ? "" : existing;
    }

    private static String findMetadataValue(Map<String, Object> metadata, List<String> keys) {
        if (metadata == null || metadata.isEmpty() || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null) {
                continue;
            }
            Object value = metadata.get(key);
            if (value == null) {
                continue;
            }
            String text = value.toString().trim();
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    private static Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long parseLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int sanitizeTokenBudget(Integer requested) {
        if (requested == null) {
            return DEFAULT_CONTEXT_TOKENS;
        }
        if (requested < 0) {
            return 0;
        }
        return Math.min(requested, MAX_CONTEXT_TOKENS);
    }

    private static ChunkReference parseChunkReference(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return null;
        }
        String trimmed = chunkId.trim();
        int split = trimmed.lastIndexOf('#');
        if (split <= 0 || split >= trimmed.length() - 1) {
            return null;
        }
        String source = trimmed.substring(0, split).trim();
        if (source.isBlank()) {
            return null;
        }
        try {
            int chunkIndex = Integer.parseInt(trimmed.substring(split + 1).trim());
            if (chunkIndex < 0) {
                return null;
            }
            return new ChunkReference(source, chunkIndex);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        String[] parts = text.trim().split("\\s+");
        return Math.max(1, parts.length);
    }

    private static void appendChunks(StringBuilder sb, List<ChunkRow> chunks) {
        for (ChunkRow chunk : chunks) {
            appendChunk(sb, chunk);
        }
    }

    private static void appendChunk(StringBuilder sb, ChunkRow chunk) {
        sb.append("[").append(chunk.source()).append("#").append(chunk.chunkIndex()).append("]\n");
        String content = chunk.content() == null ? "" : chunk.content().trim();
        if (content.length() > MAX_CHUNK_CHARS) {
            content = content.substring(0, MAX_CHUNK_CHARS - 3) + "...";
        }
        sb.append(content).append("\n");
    }

    private record ChunkReference(String source, int chunkIndex) {
    }

    private record ChunkRow(int chunkIndex, String source, String content) {
    }
}
