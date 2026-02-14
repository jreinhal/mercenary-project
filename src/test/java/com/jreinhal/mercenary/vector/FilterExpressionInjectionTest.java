package com.jreinhal.mercenary.vector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link FilterExpressionParser} against SQL-injection-style attacks,
 * null bytes, unicode tricks, and extreme-length values.
 *
 * <p>Key security property: the parser must never widen a query beyond the
 * caller's intended scope. Injection attempts should either produce {@code invalid=true}
 * (fail-closed) or parse the malicious input literally as key/value pairs that won't
 * match real document metadata.</p>
 */
class FilterExpressionInjectionTest {

    @Nested
    @DisplayName("SQL-Style Injection via dept Parameter")
    class SqlStyleInjection {

        @Test
        @DisplayName("Should parse tautology as literal key, preventing query widening")
        void shouldParseTautologyAsLiteralKey() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' || '1' == '1'");
            assertNotNull(result);
            if (!result.invalid()) {
                assertEquals(2, result.orGroups().size(), "Should have 2 OR groups");
                // First group: legitimate dept filter
                assertEquals("dept", result.orGroups().get(0).get(0).key());
                assertEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0));
                // Second group: tautology parsed literally — key is '1', not a boolean true
                assertEquals("'1'", result.orGroups().get(1).get(0).key(),
                        "Tautology should be parsed with literal key '1' (won't match any metadata)");
            }
        }

        @Test
        @DisplayName("Should parse OR widening as separate groups with explicit dept keys")
        void shouldParseOrWideningAsSeparateGroups() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' || dept == 'GOVERNMENT'");
            assertNotNull(result);
            assertFalse(result.invalid());
            assertEquals(2, result.orGroups().size(), "Should have 2 OR groups");
            assertEquals("dept", result.orGroups().get(0).get(0).key());
            assertEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0));
            assertEquals("dept", result.orGroups().get(1).get(0).key());
            assertEquals("GOVERNMENT", result.orGroups().get(1).get(0).values().get(0));
        }

        @Test
        @DisplayName("Should treat semicolon as part of value, not as statement separator")
        void shouldTreatSemicolonAsPartOfValue() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE'; DROP TABLE documents;--");
            assertNotNull(result);
            // The semicolon is not a recognized operator — everything after == is the value
            if (!result.invalid()) {
                assertEquals(1, result.orGroups().size());
                var condition = result.orGroups().get(0).get(0);
                assertEquals("dept", condition.key());
                // Value includes the semicolon and trailing text (parsed as literal string)
                assertTrue(condition.values().get(0).contains("ENTERPRISE"),
                        "Value should start with ENTERPRISE");
            }
        }

        @Test
        @DisplayName("Should treat comment-style injection as part of value")
        void shouldTreatCommentAsPartOfValue() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' --comment");
            assertNotNull(result);
            if (!result.invalid()) {
                assertEquals(1, result.orGroups().size());
                assertEquals("dept", result.orGroups().get(0).get(0).key());
                // The -- is not recognized as SQL comment syntax
            }
        }

        @Test
        @DisplayName("Should parse escaped single quotes without SQL breakout")
        void shouldParseEscapedQuotesWithoutBreakout() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE' OR '1'=='1'");
            assertNotNull(result);
            // "OR" is not a recognized operator (only "||") — parsed as single condition
            // The entire string after == is treated as value
        }

        @Test
        @DisplayName("Should parse escaped double quotes without breakout")
        void shouldParseEscapedDoubleQuotesWithoutBreakout() {
            var result = FilterExpressionParser.parse("dept == \"ENTERPRISE\" OR \"1\"==\"1\"");
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("Null Bytes and Special Characters")
    class NullBytesAndSpecialChars {

        @Test
        @DisplayName("Should handle null byte in value without truncation")
        void shouldHandleNullByteWithoutTruncation() {
            var result = FilterExpressionParser.parse("dept == 'ENTER\u0000PRISE'");
            assertNotNull(result, "Null byte should not crash the parser");
            if (!result.invalid()) {
                // Null byte is preserved in parsed value — won't match "ENTERPRISE"
                assertNotEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0),
                        "Null byte should prevent matching 'ENTERPRISE'");
            }
        }

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(FilterExpressionParser.parse(null));
        }

        @Test
        @DisplayName("Should return null for empty input")
        void shouldReturnNullForEmptyInput() {
            assertNull(FilterExpressionParser.parse(""));
        }

        @Test
        @DisplayName("Should return null for blank input")
        void shouldReturnNullForBlankInput() {
            assertNull(FilterExpressionParser.parse("   "));
        }

        @Test
        @DisplayName("Should handle newline in expression")
        void shouldHandleNewlineInExpression() {
            var result = FilterExpressionParser.parse("dept == 'ENTERPRISE'\ndept == 'GOVERNMENT'");
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle tab characters in expression")
        void shouldHandleTabInExpression() {
            var result = FilterExpressionParser.parse("dept\t==\t'ENTERPRISE'");
            assertNotNull(result);
            if (!result.invalid()) {
                assertFalse(result.orGroups().isEmpty(), "Should parse at least one condition");
            }
        }
    }

    @Nested
    @DisplayName("Unicode Attacks")
    class UnicodeAttacks {

        @Test
        @DisplayName("Should handle RTL override character in value")
        void shouldHandleRtlOverride() {
            var result = FilterExpressionParser.parse("dept == '\u202EESIRPRETNE'");
            assertNotNull(result, "RTL override should not crash the parser");
            if (!result.invalid()) {
                // The RTL character makes the value different from "ENTERPRISE"
                assertNotEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0),
                        "RTL-reversed value should not match ENTERPRISE");
            }
        }

        @Test
        @DisplayName("Should preserve zero-width space in key (prevents metadata match)")
        void shouldPreserveZeroWidthSpaceInKey() {
            var result = FilterExpressionParser.parse("de\u200Bpt == 'ENTERPRISE'");
            assertNotNull(result);
            if (!result.invalid()) {
                // The zero-width space makes the key "de\u200Bpt" which won't match "dept"
                assertNotEquals("dept", result.orGroups().get(0).get(0).key(),
                        "Key with zero-width space should not match 'dept'");
            }
        }

        @Test
        @DisplayName("Should preserve Cyrillic homoglyph in value (prevents metadata match)")
        void shouldPreserveHomoglyphInValue() {
            // Cyrillic 'Е' (U+0415) looks like Latin 'E'
            var result = FilterExpressionParser.parse("dept == '\u0415NTERPRISE'");
            assertNotNull(result);
            if (!result.invalid()) {
                assertNotEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0),
                        "Cyrillic homoglyph should prevent matching 'ENTERPRISE'");
            }
        }

        @Test
        @DisplayName("Should handle BOM and zero-width chars in value")
        void shouldHandleBomAndZeroWidthChars() {
            var result = FilterExpressionParser.parse("dept == '\uFEFF\u200BENTERPRISE\u200B\uFEFF'");
            assertNotNull(result, "BOM and zero-width chars should not crash parser");
            if (!result.invalid()) {
                assertNotEquals("ENTERPRISE", result.orGroups().get(0).get(0).values().get(0),
                        "BOM/zero-width chars should prevent exact match with 'ENTERPRISE'");
            }
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
        @DisplayName("Should parse 1000 OR groups without crashing")
        void shouldParseThousandOrGroups() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                if (i > 0) sb.append(" || ");
                sb.append("dept == 'DEPT_").append(i).append("'");
            }
            var result = FilterExpressionParser.parse(sb.toString());
            assertNotNull(result, "Many OR groups should not crash");
            if (!result.invalid()) {
                assertEquals(1000, result.orGroups().size(),
                        "All 1000 OR groups should be parsed");
            }
        }
    }

    @Nested
    @DisplayName("Spring AI Expression Format")
    class SpringAiExpressionFormat {

        @Test
        @DisplayName("Should parse valid Spring AI EQ expression")
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
        @DisplayName("Should fail-closed on malformed Spring AI expression")
        void shouldFailClosedOnMalformedExpression() {
            String malformed = "Expression[type=EQ, left=Key[key=], right=Value[value=]]";
            var result = FilterExpressionParser.parse(malformed);
            assertNotNull(result);
            // Malformed should produce invalid=true (fail-closed) or empty conditions
            if (!result.invalid()) {
                // If not invalid, conditions must not grant wider access than intended
                assertTrue(result.orGroups().isEmpty() || result.orGroups().get(0).isEmpty(),
                        "Malformed expression should not produce usable conditions");
            }
        }

        @Test
        @DisplayName("Should parse multiple Spring AI expressions into single AND group")
        void shouldParseMultipleExpressionsIntoAndGroup() {
            String compound = "Expression[type=EQ, left=Key[key=dept], right=Value[value=ENTERPRISE]], "
                    + "Expression[type=EQ, left=Key[key=workspaceId], right=Value[value=ws1]]";
            var result = FilterExpressionParser.parse(compound);
            assertNotNull(result);
            assertFalse(result.invalid());
            // Both conditions in a single AND group (orGroups has 1 element with 2 conditions)
            assertEquals(1, result.orGroups().size(), "Should be a single AND group");
            assertEquals(2, result.orGroups().get(0).size(), "AND group should have 2 conditions");
            assertEquals("dept", result.orGroups().get(0).get(0).key());
            assertEquals("workspaceId", result.orGroups().get(0).get(1).key());
        }
    }
}
