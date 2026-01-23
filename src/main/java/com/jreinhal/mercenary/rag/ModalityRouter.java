package com.jreinhal.mercenary.rag;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ModalityRouter {
    public enum ModalityTarget {
        TEXT,
        VISUAL,
        CROSS_MODAL,
        TABLE
    }

    private static final Pattern VISUAL_PATTERN = Pattern.compile("\\b(image|photo|diagram|chart|figure|map|graph|plot|screenshot|visual|picture)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_PATTERN = Pattern.compile("\\b(table|spreadsheet|csv|worksheet|tabular)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CROSS_MODAL_HINTS = Pattern.compile("\\b(report|document|evidence|source|according to|as shown|in the figure)\\b", Pattern.CASE_INSENSITIVE);

    public Set<ModalityTarget> route(String query) {
        LinkedHashSet<ModalityTarget> targets = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            targets.add(ModalityTarget.TEXT);
            return targets;
        }
        boolean wantsVisual = VISUAL_PATTERN.matcher(query).find();
        boolean wantsTable = TABLE_PATTERN.matcher(query).find();
        boolean crossModal = wantsVisual && CROSS_MODAL_HINTS.matcher(query).find();

        if (wantsVisual) {
            targets.add(ModalityTarget.VISUAL);
        }
        if (wantsTable) {
            targets.add(ModalityTarget.TABLE);
        }
        if (crossModal) {
            targets.add(ModalityTarget.CROSS_MODAL);
        }
        if (targets.isEmpty()) {
            targets.add(ModalityTarget.TEXT);
        }
        return targets;
    }
}
