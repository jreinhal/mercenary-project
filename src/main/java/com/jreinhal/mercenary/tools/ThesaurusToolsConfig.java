package com.jreinhal.mercenary.tools;

import com.jreinhal.mercenary.rag.thesaurus.DomainThesaurus;
import com.jreinhal.mercenary.rag.thesaurus.DomainThesaurus.ThesaurusMatch;
import com.jreinhal.mercenary.util.LogSanitizer;
import java.util.List;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

@Configuration
public class ThesaurusToolsConfig {
    private static final Logger log = LoggerFactory.getLogger(ThesaurusToolsConfig.class);

    @Bean
    @Description("Search the configured domain thesaurus for expansions/synonyms. Useful for acronyms and units.")
    public Function<ThesaurusSearchRequest, String> searchThesaurus(DomainThesaurus domainThesaurus) {
        return request -> {
            if (request == null || request.term() == null || request.term().isBlank()) {
                return "No term provided.";
            }
            String dept = request.department();
            int k = request.topK() != null ? request.topK().intValue() : 5;

            // Avoid logging raw user input (log forging); only log a safe summary.
            log.info("Agent Tool Invoked: searchThesaurus(termSummary={}, dept={})",
                    LogSanitizer.querySummary(request.term()), dept);

            List<ThesaurusMatch> matches = domainThesaurus.search(request.term(), dept, k);
            if (matches == null || matches.isEmpty()) {
                return "No thesaurus matches found.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Thesaurus matches:\n");
            for (ThesaurusMatch m : matches) {
                sb.append("- ").append(m.term()).append(" -> ").append(String.join(", ", m.expansions())).append("\n");
            }
            return sb.toString().trim();
        };
    }

    public record ThesaurusSearchRequest(String term, String department, Integer topK) {
    }
}

