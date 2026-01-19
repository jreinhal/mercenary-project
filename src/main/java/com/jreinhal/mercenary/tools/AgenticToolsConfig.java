/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.jreinhal.mercenary.tools.AgenticToolsConfig
 *  com.jreinhal.mercenary.tools.AgenticToolsConfig$CalculatorRequest
 *  org.slf4j.Logger
 *  org.slf4j.LoggerFactory
 *  org.springframework.context.annotation.Bean
 *  org.springframework.context.annotation.Configuration
 *  org.springframework.context.annotation.Description
 */
package com.jreinhal.mercenary.tools;

import com.jreinhal.mercenary.tools.AgenticToolsConfig;
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
            log.info("Agent Tool Invoked: currentDate() -> {}", (Object)now);
            return now;
        };
    }

    @Bean
    @Description(value="Evaluate a mathematical expression. Supports basic arithmetic (+, -, *, /) and parentheses. Use for calculations.")
    public Function<CalculatorRequest, String> calculator() {
        return request -> {
            String expr = request.expression();
            log.info("Agent Tool Invoked: calculator({})", (Object)expr);
            try {
                if (!expr.matches("[0-9+\\-*/().\\s]+")) {
                    return "Error: Invalid characters in expression.";
                }
                double result = new /* Unavailable Anonymous Inner Class!! */.parse();
                return String.valueOf(result);
            }
            catch (Exception e) {
                log.warn("Calculator error: {}", (Object)e.getMessage());
                return "Error: " + e.getMessage();
            }
        };
    }
}

