package com.jreinhal.mercenary.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FilterExpressionParserTest {

    @Test
    void parseReturnsNullForNullOrBlankInput() {
        assertNull(FilterExpressionParser.parse(null));
        assertNull(FilterExpressionParser.parse("   "));
    }

    @Test
    void parseSplitsOrAndAndGroups() {
        FilterExpressionParser.ParsedFilter parsed = FilterExpressionParser.parse(
                "dept == 'MEDICAL' && workspaceId == 'ws' || dept == 'GOVERNMENT' && documentYear >= 2020");

        assertNotNull(parsed);
        assertFalse(parsed.invalid());
        assertEquals(2, parsed.orGroups().size());
        assertEquals(2, parsed.orGroups().get(0).size());
        assertEquals(2, parsed.orGroups().get(1).size());
        assertEquals("dept", parsed.orGroups().get(0).get(0).key());
        assertEquals("==", parsed.orGroups().get(0).get(0).op());
    }

    @Test
    void parseSupportsInAndComparators() {
        FilterExpressionParser.ParsedFilter parsed =
                FilterExpressionParser.parse("dept in ['MEDICAL', 'GOVERNMENT'] && documentYear <= 2022 && score > 0.6");

        assertNotNull(parsed);
        assertFalse(parsed.invalid());
        List<FilterExpressionParser.Condition> conditions = parsed.orGroups().get(0);
        assertEquals(3, conditions.size());
        assertEquals("in", conditions.get(0).op());
        assertEquals(List.of("MEDICAL", "GOVERNMENT"), conditions.get(0).values());
        assertEquals("<=", conditions.get(1).op());
        assertEquals(">", conditions.get(2).op());
    }

    @Test
    void parseFailsClosedWhenTextCannotBeParsed() {
        FilterExpressionParser.ParsedFilter parsed = FilterExpressionParser.parse("this is not a filter expression");
        assertNotNull(parsed);
        assertTrue(parsed.invalid());
        assertTrue(parsed.orGroups().isEmpty());
    }

    @Test
    void parseSpringAiExpressionSupportsAllMappedOperators() {
        String spring = "Expression[type=EQ, left=Key[key=dept], right=Value[value=MEDICAL]],"
                + " Expression[type=NE, left=Key[key=type], right=Value[value=thesaurus]],"
                + " Expression[type=IN, left=Key[key=workspaceId], right=Value[value=a,b]],"
                + " Expression[type=GTE, left=Key[key=documentYear], right=Value[value=2020]],"
                + " Expression[type=LTE, left=Key[key=documentYear], right=Value[value=2022]],"
                + " Expression[type=GT, left=Key[key=score], right=Value[value=0.5]],"
                + " Expression[type=LT, left=Key[key=score], right=Value[value=0.9]]";

        FilterExpressionParser.ParsedFilter parsed = FilterExpressionParser.parse(spring);

        assertNotNull(parsed);
        assertFalse(parsed.invalid());
        List<FilterExpressionParser.Condition> conditions = parsed.orGroups().get(0);
        assertTrue(conditions.stream().anyMatch(c -> "==".equals(c.op()) && "dept".equals(c.key())));
        assertTrue(conditions.stream().anyMatch(c -> "!=".equals(c.op()) && "type".equals(c.key())));
        assertTrue(conditions.stream().anyMatch(c -> "in".equals(c.op())
                && "workspaceId".equals(c.key())
                && c.values().contains("a")
                && c.values().contains("b")));
        assertTrue(conditions.stream().anyMatch(c -> ">=".equals(c.op()) && "documentYear".equals(c.key())));
        assertTrue(conditions.stream().anyMatch(c -> "<=".equals(c.op()) && "documentYear".equals(c.key())));
        assertTrue(conditions.stream().anyMatch(c -> ">".equals(c.op()) && "score".equals(c.key())));
        assertTrue(conditions.stream().anyMatch(c -> "<".equals(c.op()) && "score".equals(c.key())));
    }

    @Test
    void parseSpringAiExpressionFailsClosedWhenNoKnownOperatorsFound() {
        String springUnknown = "Expression[type=UNKNOWN, left=Key[key=dept], right=Value[value=MEDICAL]]";

        FilterExpressionParser.ParsedFilter parsed = FilterExpressionParser.parse(springUnknown);

        assertNotNull(parsed);
        assertTrue(parsed.invalid());
        assertTrue(parsed.orGroups().isEmpty());
    }
}
