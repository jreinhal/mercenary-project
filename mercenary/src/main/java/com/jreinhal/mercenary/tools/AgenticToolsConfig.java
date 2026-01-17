package com.jreinhal.mercenary.tools;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

/**
 * Agentic Tools Configuration.
 * Defines functions that the LLM can invoke to perform actions or retrieval.
 */
@Configuration
public class AgenticToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(AgenticToolsConfig.class);

    @Bean
    @Description("Get the current date and time. Use this when the user asks 'what time is it' or 'what is today's date'.")
    public Function<Void, String> currentDate() {
        return unused -> {
            String now = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            log.info("Agent Tool Invoked: currentDate() -> {}", now);
            return now;
        };
    }

    /**
     * Context for calculator tool.
     */
    public record CalculatorRequest(String expression) {
    }

    @Bean
    @Description("Evaluate a mathematical expression. Supports basic arithmetic (+, -, *, /) and parentheses. Use for calculations.")
    public Function<CalculatorRequest, String> calculator() {
        return request -> {
            String expr = request.expression();
            log.info("Agent Tool Invoked: calculator({})", expr);
            try {
                // Security: Basic sanitization
                if (!expr.matches("[0-9+\\-*/().\\s]+")) {
                    return "Error: Invalid characters in expression.";
                }

                // Simple parser for safety (avoiding script engine injection risks)
                // In production, use a proper math library like exp4j
                double result = new Object() {
                    int pos = -1, ch;

                    void nextChar() {
                        ch = (++pos < expr.length()) ? expr.charAt(pos) : -1;
                    }

                    boolean eat(int charToEat) {
                        while (ch == ' ')
                            nextChar();
                        if (ch == charToEat) {
                            nextChar();
                            return true;
                        }
                        return false;
                    }

                    double parse() {
                        nextChar();
                        double x = parseExpression();
                        if (pos < expr.length())
                            throw new RuntimeException("Unexpected: " + (char) ch);
                        return x;
                    }

                    double parseExpression() {
                        double x = parseTerm();
                        for (;;) {
                            if (eat('+'))
                                x += parseTerm(); // addition
                            else if (eat('-'))
                                x -= parseTerm(); // subtraction
                            else
                                return x;
                        }
                    }

                    double parseTerm() {
                        double x = parseFactor();
                        for (;;) {
                            if (eat('*'))
                                x *= parseFactor(); // multiplication
                            else if (eat('/'))
                                x /= parseFactor(); // division
                            else
                                return x;
                        }
                    }

                    double parseFactor() {
                        if (eat('+'))
                            return parseFactor(); // unary plus
                        if (eat('-'))
                            return -parseFactor(); // unary minus

                        double x;
                        int startPos = this.pos;
                        if (eat('(')) { // parentheses
                            x = parseExpression();
                            eat(')');
                        } else if ((ch >= '0' && ch <= '9') || ch == '.') { // numbers
                            while ((ch >= '0' && ch <= '9') || ch == '.')
                                nextChar();
                            x = Double.parseDouble(expr.substring(startPos, this.pos));
                        } else {
                            throw new RuntimeException("Unexpected: " + (char) ch);
                        }
                        return x;
                    }
                }.parse();

                return String.valueOf(result);
            } catch (Exception e) {
                log.warn("Calculator error: {}", e.getMessage());
                return "Error: " + e.getMessage();
            }
        };
    }
}
