package com.jreinhal.mercenary.rag.thesaurus;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jreinhal.mercenary.util.FilterExpressionBuilder;
import com.jreinhal.mercenary.util.LogSanitizer;
import com.jreinhal.mercenary.workspace.WorkspaceContext;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

/**
 * Deterministic, configurable query enrichment for domain-specific acronyms/synonyms and (optionally) unit conversions.
 *
 * This is intentionally conservative:
 * - expansions are bounded and deduplicated
 * - unit conversions are opt-in via config
 * - vector indexing of entries is opt-in via config and uses {@code metadata.type=thesaurus}
 */
@Service
public class DomainThesaurus {
    private static final Logger log = LoggerFactory.getLogger(DomainThesaurus.class);

    private static final String GLOBAL = "GLOBAL";
    private static final String THESAURUS_TYPE = "thesaurus";

    private static final Pattern PRESSURE_PATTERN = Pattern.compile("\\b(-?\\d+(?:\\.\\d+)?)\\s*(psi|mpa)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEMP_PATTERN = Pattern.compile("\\b(-?\\d+(?:\\.\\d+)?)\\s*(?:\\u00B0\\s*)?(fahrenheit|celsius|f|c)\\b", Pattern.CASE_INSENSITIVE);
    private static final double PSI_TO_MPA = 0.006894757293168361;

    private final DomainThesaurusProperties props;
    @Nullable
    private final VectorStore vectorStore;

    private List<Entry> globalEntries = List.of();
    private Map<String, List<Entry>> deptEntries = Map.of();

    private Cache<String, Boolean> indexedCache;
    private final ConcurrentMap<String, Object> indexLocks = new ConcurrentHashMap<>();

    @Value("${sentinel.thesaurus.vector-index.cache-ttl-seconds:21600}")
    private long indexCacheTtlSeconds;

    public DomainThesaurus(DomainThesaurusProperties props, @Nullable VectorStore vectorStore) {
        this.props = props;
        this.vectorStore = vectorStore;
    }

    @PostConstruct
    public void init() {
        this.rebuildIndex();
        if (this.indexCacheTtlSeconds > 0) {
            this.indexedCache = Caffeine.newBuilder()
                    .maximumSize(500)
                    .expireAfterWrite(Duration.ofSeconds(this.indexCacheTtlSeconds))
                    .build();
        }
    }

    public boolean isEnabled() {
        return this.props != null && this.props.isEnabled();
    }

    public List<String> expandQuery(String query, @Nullable String department, int maxVariants) {
        if (!isEnabled() || query == null || query.isBlank() || maxVariants <= 0) {
            return List.of();
        }
        int cap = Math.min(Math.max(1, maxVariants), Math.max(1, this.props.getMaxQueryVariants()));

        List<Entry> entries = this.entriesFor(department);
        LinkedHashSet<String> variants = new LinkedHashSet<>();

        for (Entry entry : entries) {
            if (variants.size() >= cap) {
                break;
            }
            if (!entry.matches(query)) {
                continue;
            }
            for (String expansion : entry.expansions()) {
                if (variants.size() >= cap) {
                    break;
                }
                if (expansion == null || expansion.isBlank()) {
                    continue;
                }
                String replaced = entry.replace(query, expansion);
                if (!replaced.equalsIgnoreCase(query)) {
                    variants.add(replaced);
                    if (variants.size() >= cap) {
                        break;
                    }
                }
                String annotated = entry.replace(query, entry.term() + " (" + expansion + ")");
                if (variants.size() < cap && !annotated.equalsIgnoreCase(query)) {
                    variants.add(annotated);
                }
            }
        }

        if (this.props.isUnitConversionEnabled() && variants.size() < cap) {
            variants.addAll(unitConversionVariants(query, cap - variants.size()));
        }

        if (log.isDebugEnabled() && !variants.isEmpty()) {
            log.debug("DomainThesaurus: {} variant(s) for query {}", variants.size(), LogSanitizer.querySummary(query));
        }
        return new ArrayList<>(variants);
    }

    /**
     * Lexical + (optional) semantic search over configured entries.
     */
    public List<ThesaurusMatch> search(String term, @Nullable String department, int topK) {
        if (!isEnabled() || term == null || term.isBlank() || topK <= 0) {
            return List.of();
        }
        int k = Math.min(20, Math.max(1, topK));

        List<ThesaurusMatch> lexical = lexicalSearch(term, department, k);
        if (!props.isVectorIndexEnabled() || vectorStore == null) {
            return lexical;
        }

        String workspaceId = WorkspaceContext.getCurrentWorkspaceId();
        String dept = normalizeDept(department);
        if (workspaceId == null || workspaceId.isBlank() || dept == null) {
            return lexical;
        }

        ensureIndexed(dept, workspaceId);
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.query(term)
                    .withTopK(Math.min(10, k))
                    .withSimilarityThreshold(0.2)
                    .withFilterExpression(FilterExpressionBuilder.forDepartmentAndWorkspaceAndType(dept, workspaceId, THESAURUS_TYPE)));

            if (docs == null || docs.isEmpty()) {
                return lexical;
            }

            LinkedHashMap<String, ThesaurusMatch> merged = new LinkedHashMap<>();
            for (ThesaurusMatch lexicalMatch : lexical) {
                merged.putIfAbsent(normalizeTermKey(lexicalMatch.term()), lexicalMatch);
            }
            for (Document d : docs) {
                Object t = d.getMetadata().get("term");
                Object ex = d.getMetadata().get("expansions");
                String matchTerm = t != null ? t.toString() : "";
                List<String> expansions = ex instanceof List<?> list ? list.stream().map(Objects::toString).toList() : List.of();
                if (!matchTerm.isBlank()) {
                    merged.putIfAbsent(normalizeTermKey(matchTerm), new ThesaurusMatch(matchTerm, expansions, dept, "semantic"));
                }
                if (merged.size() >= k) {
                    break;
                }
            }
            return new ArrayList<>(merged.values());
        } catch (Exception e) {
            // Fail open: a thesaurus search tool should not break the main RAG loop.
            if (log.isDebugEnabled()) {
                log.debug("DomainThesaurus semantic search failed: {}", e.getMessage());
            }
            return lexical;
        }
    }

    private List<ThesaurusMatch> lexicalSearch(String term, @Nullable String department, int topK) {
        String needle = term.trim().toLowerCase(Locale.ROOT);
        List<Entry> entries = this.entriesFor(department);
        ArrayList<ScoredMatch> matches = new ArrayList<>();
        for (Entry entry : entries) {
            int score = entry.lexicalScore(needle);
            if (score <= 0) {
                continue;
            }
            matches.add(new ScoredMatch(new ThesaurusMatch(entry.term(), entry.expansions(), entry.dept(), "lexical"), score));
        }
        matches.sort((a, b) -> Integer.compare(b.score(), a.score()));
        ArrayList<ThesaurusMatch> out = new ArrayList<>();
        for (ScoredMatch sm : matches) {
            out.add(sm.match());
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    private void ensureIndexed(String dept, String workspaceId) {
        if (!props.isVectorIndexEnabled() || vectorStore == null) {
            return;
        }
        String cacheKey = dept + "|" + workspaceId;
        if (this.indexedCache != null && Boolean.TRUE.equals(this.indexedCache.getIfPresent(cacheKey))) {
            return;
        }
        Object lock = this.indexLocks.computeIfAbsent(cacheKey, k -> new Object());
        try {
            synchronized (lock) {
                if (this.indexedCache != null && Boolean.TRUE.equals(this.indexedCache.getIfPresent(cacheKey))) {
                    return;
                }
                List<Entry> entries = this.entriesFor(dept);
                if (entries.isEmpty()) {
                    if (this.indexedCache != null) {
                        this.indexedCache.put(cacheKey, Boolean.TRUE);
                    }
                    return;
                }

                List<Document> docs = new ArrayList<>(entries.size());
                for (Entry entry : entries) {
                    String id = "thesaurus|" + workspaceId + "|" + dept + "|" + entry.term().toUpperCase(Locale.ROOT);
                    String content = "THESAURUS\n\nTERM: " + entry.term() + "\nEXPANSIONS: " + String.join(", ", entry.expansions());
                    Document d = new Document(id, content, Map.of(
                            "type", THESAURUS_TYPE,
                            "dept", dept,
                            "workspaceId", workspaceId,
                            "term", entry.term(),
                            "expansions", entry.expansions()
                    ));
                    docs.add(d);
                }
                vectorStore.add(docs);
                if (this.indexedCache != null) {
                    this.indexedCache.put(cacheKey, Boolean.TRUE);
                }
                log.info("DomainThesaurus: indexed {} entries for dept={} workspace={}", docs.size(), dept, workspaceId);
            }
        } finally {
            this.indexLocks.remove(cacheKey, lock);
        }
    }

    private List<Entry> entriesFor(@Nullable String department) {
        String dept = normalizeDept(department);
        List<Entry> out = new ArrayList<>();
        out.addAll(this.globalEntries);
        if (dept != null) {
            out.addAll(this.deptEntries.getOrDefault(dept, List.of()));
        }
        return out;
    }

    private static String normalizeDept(@Nullable String department) {
        if (department == null || department.isBlank()) {
            return null;
        }
        String d = department.trim().toUpperCase(Locale.ROOT);
        return d;
    }

    private static String normalizeTermKey(String term) {
        if (term == null) {
            return "";
        }
        return term.trim().toLowerCase(Locale.ROOT);
    }

    private void rebuildIndex() {
        Map<String, Map<String, List<String>>> raw = props != null ? props.getEntries() : Map.of();
        if (raw == null || raw.isEmpty()) {
            this.globalEntries = List.of();
            this.deptEntries = Map.of();
            return;
        }

        ArrayList<Entry> globals = new ArrayList<>();
        java.util.HashMap<String, List<Entry>> byDept = new java.util.HashMap<>();

        for (Map.Entry<String, Map<String, List<String>>> deptEntry : raw.entrySet()) {
            String dept = deptEntry.getKey() == null ? "" : deptEntry.getKey().trim().toUpperCase(Locale.ROOT);
            Map<String, List<String>> terms = deptEntry.getValue();
            if (dept.isBlank() || terms == null || terms.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, List<String>> termEntry : terms.entrySet()) {
                String term = termEntry.getKey();
                if (term == null || term.isBlank()) {
                    continue;
                }
                List<String> expansions = termEntry.getValue() == null ? List.of() : termEntry.getValue().stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                if (expansions.isEmpty()) {
                    continue;
                }
                Entry e = Entry.of(dept, term.trim(), expansions);
                if (GLOBAL.equalsIgnoreCase(dept)) {
                    globals.add(e);
                } else {
                    byDept.computeIfAbsent(dept, k -> new ArrayList<>()).add(e);
                }
            }
        }

        this.globalEntries = Collections.unmodifiableList(globals);
        java.util.HashMap<String, List<Entry>> frozen = new java.util.HashMap<>();
        for (Map.Entry<String, List<Entry>> e : byDept.entrySet()) {
            frozen.put(e.getKey(), Collections.unmodifiableList(e.getValue()));
        }
        this.deptEntries = Collections.unmodifiableMap(frozen);
    }

    private static List<String> unitConversionVariants(String query, int cap) {
        if (query == null || query.isBlank() || cap <= 0) {
            return List.of();
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();

        Matcher p = PRESSURE_PATTERN.matcher(query);
        if (p.find() && out.size() < cap) {
            double value = Double.parseDouble(p.group(1));
            String unit = p.group(2).toLowerCase(Locale.ROOT);
            String replacement;
            if ("psi".equals(unit)) {
                replacement = format(value * PSI_TO_MPA) + " MPa";
            } else {
                replacement = format(value / PSI_TO_MPA) + " psi";
            }
            out.add(replaceSpan(query, p.start(), p.end(), replacement));
        }

        Matcher t = TEMP_PATTERN.matcher(query);
        if (t.find() && out.size() < cap) {
            double value = Double.parseDouble(t.group(1));
            String unit = t.group(2).toLowerCase(Locale.ROOT);
            String replacement;
            if (unit.startsWith("f")) {
                replacement = format((value - 32.0) * 5.0 / 9.0) + " C";
            } else {
                replacement = format(value * 9.0 / 5.0 + 32.0) + " F";
            }
            out.add(replaceSpan(query, t.start(), t.end(), replacement));
        }

        return new ArrayList<>(out);
    }

    private static String replaceSpan(String s, int start, int end, String replacement) {
        return s.substring(0, start) + replacement + s.substring(end);
    }

    private static String format(double value) {
        // Prefer stable formatting for retrieval (avoid scientific notation).
        String s = String.format(Locale.ROOT, "%.3f", value);
        int dot = s.indexOf('.');
        if (dot < 0) {
            return s;
        }
        int end = s.length();
        while (end > dot + 1 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > dot && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    private record ScoredMatch(ThesaurusMatch match, int score) {}

    public record ThesaurusMatch(String term, List<String> expansions, String department, String source) {}

    private record Entry(String dept, String term, List<String> expansions, Pattern pattern, String termLower) {
        static Entry of(String dept, String term, List<String> expansions) {
            boolean wordBoundaries = term.matches("[A-Za-z0-9_]{2,}");
            String regex = wordBoundaries
                    ? "(?i)\\b" + Pattern.quote(term) + "\\b"
                    : "(?i)" + Pattern.quote(term);
            Pattern pattern = Pattern.compile(regex);
            return new Entry(dept, term, expansions, pattern, term.toLowerCase(Locale.ROOT));
        }

        boolean matches(String query) {
            return pattern.matcher(query).find();
        }

        String replace(String query, String replacement) {
            return pattern.matcher(query).replaceAll(Matcher.quoteReplacement(replacement));
        }

        int lexicalScore(String needleLower) {
            if (needleLower.equals(termLower)) {
                return 100;
            }
            if (termLower.contains(needleLower) || needleLower.contains(termLower)) {
                return 60;
            }
            for (String e : expansions) {
                if (e == null) {
                    continue;
                }
                String lower = e.toLowerCase(Locale.ROOT);
                if (lower.equals(needleLower)) {
                    return 90;
                }
                if (lower.contains(needleLower)) {
                    return 40;
                }
            }
            return 0;
        }
    }
}
