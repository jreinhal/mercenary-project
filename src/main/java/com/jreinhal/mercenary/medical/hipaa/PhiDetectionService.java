/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.medical.hipaa.PhiDetectionService
 *  com.jreinhal.mercenary.medical.hipaa.PhiDetectionService$PhiMatch
 *  com.jreinhal.mercenary.medical.hipaa.PhiDetectionService$PhiType
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.stereotype.Service
 */
package com.jreinhal.mercenary.medical.hipaa;

import com.jreinhal.mercenary.medical.hipaa.PhiDetectionService;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PhiDetectionService {
    private static final Logger log = LoggerFactory.getLogger(PhiDetectionService.class);
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b|\\b\\d{9}\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b(?:\\+?1[-.]?)?\\(?\\d{3}\\)?[-.]?\\d{3}[-.]?\\d{4}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern ZIP_PATTERN = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b(?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)?\\d{2}\\b|\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+\\d{1,2},?\\s+\\d{4}\\b", 2);
    private static final Pattern IP_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\b");
    private static final Pattern MRN_PATTERN = Pattern.compile("\\b(?:MRN|MR#|Medical Record)[:\\s]*([A-Z0-9-]{6,15})\\b|\\b[A-Z]{2,3}\\d{6,10}\\b", 2);
    private static final Pattern URL_PATTERN = Pattern.compile("\\bhttps?://[^\\s]+\\b");

    public List<PhiMatch> detectPhi(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        ArrayList<PhiMatch> matches = new ArrayList<PhiMatch>();
        matches.addAll(this.findMatches(text, SSN_PATTERN, PhiType.SSN, 0.95));
        matches.addAll(this.findMatches(text, PHONE_PATTERN, PhiType.PHONE, 0.85));
        matches.addAll(this.findMatches(text, EMAIL_PATTERN, PhiType.EMAIL, 0.9));
        matches.addAll(this.findMatches(text, ZIP_PATTERN, PhiType.ZIP_CODE, 0.7));
        matches.addAll(this.findMatches(text, DATE_PATTERN, PhiType.DATE_OF_BIRTH, 0.75));
        matches.addAll(this.findMatches(text, IP_PATTERN, PhiType.IP_ADDRESS, 0.9));
        matches.addAll(this.findMatches(text, MRN_PATTERN, PhiType.MRN, 0.85));
        matches.addAll(this.findMatches(text, URL_PATTERN, PhiType.URL, 0.8));
        log.debug("PHI detection found {} potential identifiers", (Object)matches.size());
        return matches;
    }

    public boolean containsPhi(String text) {
        return !this.detectPhi(text).isEmpty();
    }

    public Map<PhiType, Integer> countPhiByType(String text) {
        List matches = this.detectPhi(text);
        EnumMap<PhiType, Integer> counts = new EnumMap<PhiType, Integer>(PhiType.class);
        for (PhiMatch match : matches) {
            counts.merge(match.type(), 1, Integer::sum);
        }
        return counts;
    }

    public String redactPhi(String text) {
        if (text == null) {
            return null;
        }
        List matches = this.detectPhi(text);
        matches.sort((a, b) -> Integer.compare(b.startIndex(), a.startIndex()));
        StringBuilder result = new StringBuilder(text);
        for (PhiMatch match : matches) {
            String replacement = "[" + match.type().name() + "]";
            result.replace(match.startIndex(), match.endIndex(), replacement);
        }
        return result.toString();
    }

    private List<PhiMatch> findMatches(String text, Pattern pattern, PhiType type, double confidence) {
        ArrayList<PhiMatch> matches = new ArrayList<PhiMatch>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            matches.add(new PhiMatch(type, matcher.group(), matcher.start(), matcher.end(), confidence));
        }
        return matches;
    }

    public String getPhiSummary(String text) {
        List matches = this.detectPhi(text);
        if (matches.isEmpty()) {
            return "No PHI detected";
        }
        Map counts = this.countPhiByType(text);
        StringBuilder summary = new StringBuilder("PHI detected: ");
        counts.forEach((type, count) -> summary.append(type.getDisplayName()).append("(").append(count).append(") "));
        return summary.toString().trim();
    }
}

