package com.jreinhal.mercenary.rag.thesaurus;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DomainThesaurusTest {

    @Test
    void expandsAcronymsFromConfiguredEntries() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setUnitConversionEnabled(false);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of(
                "GLOBAL", Map.of(
                        "HIPAA", List.of("Health Insurance Portability and Accountability Act")
                ),
                "GOVERNMENT", Map.of(
                        "LOX", List.of("Liquid Oxygen")
                )
        ));

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> hipaa = thesaurus.expandQuery("What is HIPAA compliance?", "MEDICAL", 5);
        assertTrue(hipaa.stream().anyMatch(v -> v.toLowerCase().contains("health insurance portability")),
                "Expected HIPAA expansion to appear in variants");

        List<String> lox = thesaurus.expandQuery("LOX tank pressure", "GOVERNMENT", 5);
        assertTrue(lox.stream().anyMatch(v -> v.toLowerCase().contains("liquid oxygen")),
                "Expected LOX expansion to appear in variants");
    }

    @Test
    void expandsUnitConversionsWhenEnabled() {
        DomainThesaurusProperties props = new DomainThesaurusProperties();
        props.setEnabled(true);
        props.setUnitConversionEnabled(true);
        props.setMaxQueryVariants(10);
        props.setEntries(Map.of());

        DomainThesaurus thesaurus = new DomainThesaurus(props, null);
        thesaurus.init();

        List<String> variants = thesaurus.expandQuery("max pressure 100 psi", "ENTERPRISE", 5);
        assertTrue(variants.stream().anyMatch(v -> v.toLowerCase().contains("mpa")),
                "Expected PSI->MPa conversion variant");
    }
}

