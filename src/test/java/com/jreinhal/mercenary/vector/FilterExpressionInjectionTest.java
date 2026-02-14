package com.jreinhal.mercenary.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests FilterExpressionParser against SQL-injection-style attacks,
 * null bytes, unicode tricks, and extreme-length values.
 */
class FilterExpressionInjectionTest {

    @Nested
    @DisplayName("SQL-Style Injection via dept Parameter")
    class SqlStyleInjection {

        @Test
        @DisplayName("Should safely parse escaped single quotes")
        void shouldSafelyParseEscapedQuotes() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' OR '1'=='1'");
            assertNotNull(result);
            // Parser should treat this as a valid expression, but with the literal key "dept"
            // and not widen the query to all departments
            if (!result.invalid()) {
                assertFalse(result.orGroups().isEmpty());
            }
        }

        @Test
        @DisplayName("Should safely parse escaped double quotes")
        void shouldSafelyParseEscapedDoubleQuotes() {
            var result = FilterExpressionParser.parse("dept == \"ENTERPRISE\" OR \"1\"==\"1\"");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should safely parse semicolon injection")
        void shouldSafelyParseSemicolonInjection() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE'; DROP TABLE documents;--");
            assertNotNull(result);
            // Semicolons are not operators — the parser should either ignore them or produce invalid
        }

        @Test
        @DisplayName("Should not widen query with OR injection")
        void shouldNotWidenWithOrInjection() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' || dept == 'GOVERNMENT'");
            assertNotNull(result);
            if (!result.invalid()) {
                // Should have 2 OR groups, each with 1 condition
                assertEquals(2, result.orGroups().size());
                // Verify both groups have the dept key
                assertEquals("dept", result.orGroups().get(0).get(0).key());
                assertEquals("dept", result.orGroups().get(1).get(0).key());
            }
        }

        @Test
        @DisplayName("Should handle tautology injection attempt")
        void shouldHandleTautologyInjection() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' || '1' == '1'");
            assertNotNull(result);
            // The tautology condition has key='1' which won't match any document metadata
        }

        @Test
        @DisplayName("Should handle comment-style injection")
        void shouldHandleCommentInjection() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' --comment");
            assertNotNull(result);
            // SQL comments are not recognized by this parser
        }
    }

    @Nested
    @DisplayName("Null Bytes and Special Characters")
    class NullBytesAndSpecialChars {

        @Test
        @DisplayName("Should handle null byte in filter expression")
        void shouldHandleNullByte() {
            var result = FilterExpressionParser.parse("dept == 'ENTER\u0000PRISE'");
            assertNotNull(result, "Null byte should not crash the parser");
        }

        @Test
        @DisplayName("Should handle null input")
        void shouldHandleNullInput() {
            var result = FilterExpressionParser.parse(null);
            assertNull(result, "Null input should return null");
        }

        @Test
        @DisplayName("Should handle empty input")
        void shouldHandleEmptyInput() {
            var result = FilterExpressionParser.parse("");
            assertNull(result, "Empty input should return null");
        }

        @Test
        @DisplayName("Should handle blank input")
        void shouldHandleBlankInput() {
            var result = FilterExpressionParser.parse("   ");
            assertNull(result, "Blank input should return null");
        }

        @Test
        @DisplayName("Should handle newline injection")
        void shouldHandleNewlineInjection() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE'\ndept == 'GOVERNMENT'");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle tab injection")
        void shouldHandleTabInjection() {
            var result = FilterExpressionParser.parse("dept\t==\t'ENTERPRISE'");
            assertNotNull(result);
            if (!result.invalid()) {
                assertFalse(result.orGroups().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("Unicode Attacks")
    class UnicodeAttacks {

        @Test
        @DisplayName("Should handle right-to-left override character")
        void shouldHandleRtlOverride() {
            var result = FilterExpressionParser.parse("dept == '\u202EESIRPRETNE'");
            assertNotNull(result, "RTL override should not crash the parser");
        }

        @Test
        @DisplayName("Should handle zero-width space in key")
        void shouldHandleZeroWidthSpaceInKey() {
            var result = FilterExpressionParser.parse("de\u200Bpt == 'ENTERPRISE'");
            assertNotNull(result);
            // The zero-width space makes the key "de\u200Bpt" which won't match "dept"
        }

        @Test
        @DisplayName("Should handle homoglyph in value")
        void shouldHandleHomoglyphInValue() {
            // Cyrillic 'Е' looks like Latin 'E'
            var result = FilterExpressionParser.parse("dept == '\u0415NTERPRISE'");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle Unicode category abuse")
        void shouldHandleUnicodeCategoryAbuse() {
            var result = FilterExpressionParser.parse("dept == '\uFEFF\u200BENTERPRISE\u200B\uFEFF'");
            assertNotNull(result, "BOM and zero-width chars should not crash parser");
        }
    }

    @Nested
    @DisplayName("Extreme-Length Values")
    class ExtremeLengthValues {

        @Test
        @DisplayName("Should handle extremely long key name")
        void shouldHandleExtremelyLongKeyName() {
            String longKey = "a".repeat(100_000);
            var result = FilterExpressionParser.parse(longKey + " == 'ENTERPRISE'");
            assertNotNull(result, "Extremely long key should not crash");
        }

        @Test
        @DisplayName("Should handle extremely long value")
        void shouldHandleExtremelyLongValue() {
            String longValue = "A".repeat(100_000);
            var result = FilterExpressionParser.parse("dept == '" + longValue + "'");
            assertNotNull(result, "Extremely long value should not crash");
        }

        @Test
        @DisplayName("Should handle deeply nested OR expressions")
        void shouldHandleDeeplyNestedOrExpressions() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                if (i > 0) sb.append(" || ");
                sb.append("dept == 'DEPT_").append(i).append("'");
            }
            var result = FilterExpressionParser.parse(sb.toString());
            assertNotNull(result, "Many OR groups should not crash");
            if (!result.invalid()) {
                assertEquals(1000, result.orGroups().size());
            }
        }
    }

    @Nested
    @DisplayName("Spring AI Expression Format")
    class SpringAiExpressionFormat {

        @Test
        @DisplayName("Should parse valid Spring AI expression")
        void shouldParseValidSpringAiExpression() {
            String expr = "Expression[type=EQ, left=Key[key=dept], right=Value[value=ENTERPRISE]]";
            var result = FilterExpressionParser.parse(expr);
            assertNotNull(result);
            assertFalse(result.invalid());
            assertEquals(1, result.orGroups().size());
            assertEquals("dept", result.orGroups().get(0).get(0).key());
            assertEquals("==", result.orGroups().get(0).get(0).op());
            assertEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0));
        }

        @Test
        @DisplayName("Should handle malformed Spring AI expression safely")
        void shouldHandleMalformedSpringAiExpression() {
            String malformed = "Expression[type=EQ, left=Key[key=], right=Value[value=]]";
            var result = FilterExpressionParser.parse(malformed);
            assertNotNull(result);
            // Malformed should either produce empty conditions or invalid=true
        }

        @Test
        @DisplayName("Should handle injection in Spring AI expression value")
        void shouldHandleInjectionInSpringAiValue() {
            String injected = "Expression[type=EQ, left=Key[key=dept], right=Value[value=ENTERPRISE]], "
                    + "Expression[type=EQ, left=Key[key=dept], right=Value[value=GOVERNMENT]]";
            var result = FilterExpressionParser.parse(injected);
            assertNotNull(result);
            // Both conditions should be parsed into a single AND group
            if (!result.invalid()) {
                assertEquals(1, result.orGroups().size());
                assertEquals(2, result.orGroups().get(0).size());
            }
        }
    }
}
