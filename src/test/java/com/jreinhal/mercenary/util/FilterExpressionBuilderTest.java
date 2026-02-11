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
    void forDepartmentAndTypeBuildsExactTypeFilter() {
        String expr = FilterExpressionBuilder.forDepartmentAndType("MEDICAL", "table");
        assertEquals("dept == 'MEDICAL' && type == 'table'", expr);
    }

    @Test
    void forDepartmentAndWorkspaceAndTypeBuildsWorkspaceTypeFilter() {
        String expr = FilterExpressionBuilder.forDepartmentAndWorkspaceAndType("GOVERNMENT", "ws-1", "thesaurus");
        assertEquals("dept == 'GOVERNMENT' && workspaceId == 'ws-1' && type == 'thesaurus'", expr);
    }

    @Test
    void andHandlesBlankInputs() {
        assertEquals("x", FilterExpressionBuilder.and("", "x"));
        assertEquals("x", FilterExpressionBuilder.and("x", ""));
        assertEquals("a && b", FilterExpressionBuilder.and("a", "b"));
    }

    @Test
    void andHandlesNullInputs() {
        assertEquals("x", FilterExpressionBuilder.and(null, "x"));
        assertEquals("x", FilterExpressionBuilder.and("x", null));
        assertEquals("", FilterExpressionBuilder.and(null, null));
    }

    @Test
    void forDepartmentAndWorkspaceExcludingTypeAddsThesaurusExclusionForOtherTypes() {
        String expr = FilterExpressionBuilder.forDepartmentAndWorkspaceExcludingType("MEDICAL", "ws", "image");
        assertEquals("dept == 'MEDICAL' && workspaceId == 'ws' && type != 'image' && type != 'thesaurus'", expr);
    }
}
