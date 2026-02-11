package com.jreinhal.mercenary.tools;

import com.jreinhal.mercenary.service.AgentToolService;
import com.jreinhal.mercenary.util.LogSanitizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

@Configuration
public class AgenticToolsConfig {
    private static final Logger log = LoggerFactory.getLogger(AgenticToolsConfig.class);

    @Bean
    @Description(value="Get the current date and time. Use this when the user asks 'what time is it' or 'what is today's date'.")
    public Function<Void, String> currentDate() {
        return unused -> {
            String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            log.info("Agent Tool Invoked: currentDate() -> {}", now);
            return now;
        };
    }

    @Bean
    @Description(value="Evaluate a mathematical expression. Supports basic arithmetic (+, -, *, /) and parentheses. Use for calculations.")
    public Function<CalculatorRequest, String> calculator() {
        return request -> {
            final String expr = request.expression();
            log.info("Agent Tool Invoked: calculator({})", expr);
            try {
                if (!expr.matches("[0-9+\\-*/().\\s]+")) {
                    return "Error: Invalid characters in expression.";
                }
                double result = evaluateExpression(expr);
                return String.valueOf(result);
            }
            catch (Exception e) {
                log.warn("Calculator error: {}", e.getMessage());
                return "Error: " + e.getMessage();
            }
        };
    }

    @Bean
    @Description("Get lightweight metadata for a document (title/date/page estimate/chunk count) by documentId or filename.")
    public Function<DocumentInfoRequest, String> getDocumentInfo(AgentToolService agentToolService) {
        return request -> {
            if (request == null || request.documentId() == null || request.documentId().isBlank()) {
                return "No documentId provided.";
            }
            if (log.isInfoEnabled()) {
                log.info("Agent Tool Invoked: getDocumentInfo(documentIdSummary={}, dept={})",
                        LogSanitizer.querySummary(request.documentId()),
                        LogSanitizer.querySummary(request.department()));
            }
            return agentToolService.getDocumentInfo(request.documentId(), request.department());
        };
    }

    @Bean
    @Description("Get text context around a specific chunkId using source#chunk_index and token budgets before/after.")
    public Function<AdjacentChunksRequest, String> getAdjacentChunks(AgentToolService agentToolService) {
        return request -> {
            if (request == null || request.chunkId() == null || request.chunkId().isBlank()) {
                return "No chunkId provided.";
            }
            if (log.isInfoEnabled()) {
                log.info("Agent Tool Invoked: getAdjacentChunks(chunkIdSummary={}, beforeTokens={}, afterTokens={}, dept={})",
                        LogSanitizer.querySummary(request.chunkId()),
                        request.beforeTokens(),
                        request.afterTokens(),
                        LogSanitizer.querySummary(request.department()));
            }
            return agentToolService.getAdjacentChunks(
                    request.chunkId(),
                    request.beforeTokens(),
                    request.afterTokens(),
                    request.department());
        };
    }

    private double evaluateExpression(String expr) {
        return new ExpressionParser(expr).parse();
    }

    private static class ExpressionParser {
        private final String expr;
        private int pos = -1;
        private int ch;

        ExpressionParser(String expr) {
            this.expr = expr;
        }

        void nextChar() {
            ch = ++pos < expr.length() ? expr.charAt(pos) : -1;
        }

        boolean eat(int charToEat) {
            while (ch == ' ') {
                nextChar();
            }
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        double parse() {
            nextChar();
            double x = parseExpression();
            if (pos < expr.length()) {
                throw new RuntimeException("Unexpected: " + (char)ch);
            }
            return x;
        }

        double parseExpression() {
            double x = parseTerm();
            while (true) {
                if (eat('+')) {
                    x += parseTerm();
                } else if (eat('-')) {
                    x -= parseTerm();
                } else {
                    break;
                }
            }
            return x;
        }

        double parseTerm() {
            double x = parseFactor();
            while (true) {
                if (eat('*')) {
                    x *= parseFactor();
                } else if (eat('/')) {
                    x /= parseFactor();
                } else {
                    break;
                }
            }
            return x;
        }

        double parseFactor() {
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }
            double x;
            int startPos = pos;
            if (eat('(')) {
                x = parseExpression();
                eat(')');
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    nextChar();
                }
                x = Double.parseDouble(expr.substring(startPos, pos));
            } else {
                throw new RuntimeException("Unexpected: " + (char)ch);
            }
            return x;
        }
    }

    public record CalculatorRequest(String expression) {
    }

    public record DocumentInfoRequest(String documentId, String department) {
    }

    public record AdjacentChunksRequest(String chunkId, Integer beforeTokens, Integer afterTokens, String department) {
    }
}
