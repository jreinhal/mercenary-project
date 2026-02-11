package com.jreinhal.mercenary.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TemporalQueryConstraintsTest {

    @Test
    void buildsYearRangeFilterForBetweenAnd() {
        String filter = TemporalQueryConstraints.buildDocumentYearFilter("budget between 2020 and 2022");
        assertEquals("documentYear >= 2020 && documentYear <= 2022", filter);
    }

    @Test
    void buildsExactYearFilterForIn() {
        String filter = TemporalQueryConstraints.buildDocumentYearFilter("in 2021 what changed");
        assertEquals("documentYear == 2021", filter);
    }

    @Test
    void supportsDashYearRangesWithoutHintWords() {
        String filter = TemporalQueryConstraints.buildDocumentYearFilter("policy changes 2019-2020");
        assertEquals("documentYear >= 2019 && documentYear <= 2020", filter);
    }

    @Test
    void avoidsAccidentalYearMatchesWhenNoTemporalHints() {
        assertTrue(TemporalQueryConstraints.buildDocumentYearFilter("DOC-ID ABC-2020-XYZ").isBlank());
    }
}

