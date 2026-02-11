package com.jreinhal.mercenary.vector;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FilterExpressionEvaluatorTest {

    @Test
    void supportsNumericComparators() {
        FilterExpressionParser.ParsedFilter parsed =
                FilterExpressionParser.parse("dept == 'MEDICAL' && documentYear >= 2020 && documentYear <= 2022");

        assertTrue(FilterExpressionEvaluator.matches(Map.of("dept", "MEDICAL", "documentYear", 2021), parsed));
        assertFalse(FilterExpressionEvaluator.matches(Map.of("dept", "MEDICAL", "documentYear", 2019), parsed));
        assertFalse(FilterExpressionEvaluator.matches(Map.of("dept", "ENTERPRISE", "documentYear", 2021), parsed));
        assertFalse(FilterExpressionEvaluator.matches(Map.of("dept", "MEDICAL"), parsed), "Missing documentYear should fail a range condition");
    }

    @Test
    void supportsOrGroups() {
        FilterExpressionParser.ParsedFilter parsed =
                FilterExpressionParser.parse("dept == 'MEDICAL' && documentYear == 2021 || dept == 'GOVERNMENT' && documentYear == 2020");

        assertTrue(FilterExpressionEvaluator.matches(Map.of("dept", "MEDICAL", "documentYear", 2021), parsed));
        assertTrue(FilterExpressionEvaluator.matches(Map.of("dept", "GOVERNMENT", "documentYear", 2020), parsed));
        assertFalse(FilterExpressionEvaluator.matches(Map.of("dept", "GOVERNMENT", "documentYear", 2021), parsed));
    }

    @Test
    void supportsInOperator() {
        FilterExpressionParser.ParsedFilter parsed =
                FilterExpressionParser.parse("dept in ['MEDICAL','GOVERNMENT'] && documentYear >= 2020");

        assertTrue(FilterExpressionEvaluator.matches(Map.of("dept", "MEDICAL", "documentYear", 2020), parsed));
        assertTrue(FilterExpressionEvaluator.matches(Map.of("dept", "GOVERNMENT", "documentYear", 2022), parsed));
        assertFalse(FilterExpressionEvaluator.matches(Map.of("dept", "ENTERPRISE", "documentYear", 2022), parsed));
    }
}

