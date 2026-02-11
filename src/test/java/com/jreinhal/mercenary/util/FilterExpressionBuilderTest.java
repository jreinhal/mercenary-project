package com.jreinhal.mercenary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FilterExpressionBuilderTest {

    @Test
    void andHandlesBlankInputs() {
        assertEquals("b", FilterExpressionBuilder.and("", "b"));
        assertEquals("a", FilterExpressionBuilder.and("a", ""));
        assertEquals("", FilterExpressionBuilder.and("", ""));
    }

    @Test
    void forDepartmentEscapesValuesAndExcludesThesaurus() {
        String expr = FilterExpressionBuilder.forDepartment("O'Hara\\X");
        assertEquals("dept == 'O\\'Hara\\\\X' && type != 'thesaurus'", expr);
    }

    @Test
    void forDepartmentExcludingTypeAddsDefaultThesaurusExclusion() {
        String expr = FilterExpressionBuilder.forDepartmentExcludingType("ENTERPRISE", "visual");
        assertEquals("dept == 'ENTERPRISE' && type != 'visual' && type != 'thesaurus'", expr);
    }

    @Test
    void forDepartmentExcludingTypeDoesNotDuplicateThesaurusExclusionWhenAlreadyExcludingThesaurus() {
        String expr = FilterExpressionBuilder.forDepartmentExcludingType("ENTERPRISE", "thesaurus");
        assertEquals("dept == 'ENTERPRISE' && type != 'thesaurus'", expr);
    }

    @Test
    void forDepartmentAndWorkspaceExcludingTypeDoesNotDuplicateThesaurusExclusionWhenAlreadyExcludingThesaurus() {
        String expr = FilterExpressionBuilder.forDepartmentAndWorkspaceExcludingType("ENTERPRISE", "ws", "THESAURUS");
        assertEquals("dept == 'ENTERPRISE' && workspaceId == 'ws' && type != 'THESAURUS'", expr);
    }

    @Test
    void forDepartmentAndWorkspaceIncludesWorkspaceAndExcludesThesaurus() {
        String expr = FilterExpressionBuilder.forDepartmentAndWorkspace("ENTERPRISE", "ws");
        assertTrue(expr.contains("workspaceId == 'ws'"));
        assertTrue(expr.contains("type != 'thesaurus'"));
    }
}

