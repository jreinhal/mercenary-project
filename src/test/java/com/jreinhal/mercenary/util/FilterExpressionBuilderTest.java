package com.jreinhal.mercenary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FilterExpressionBuilderTest {

    @Test
    void forDepartmentAndWorkspaceExcludesThesaurusByDefault() {
        String filter = FilterExpressionBuilder.forDepartmentAndWorkspace("MEDICAL", "ws");
        assertTrue(filter.contains("type != 'thesaurus'"));
    }

    @Test
    void excludingThesaurusDoesNotAddDuplicateCondition() {
        String filter = FilterExpressionBuilder.forDepartmentAndWorkspaceExcludingType("MEDICAL", "ws", "thesaurus");
        int occurrences = filter.split("type != 'thesaurus'", -1).length - 1;
        assertEquals(1, occurrences);
    }

    @Test
    void andHandlesBlankInputs() {
        assertEquals("x", FilterExpressionBuilder.and("", "x"));
        assertEquals("x", FilterExpressionBuilder.and("x", ""));
        assertEquals("a && b", FilterExpressionBuilder.and("a", "b"));
    }
}

