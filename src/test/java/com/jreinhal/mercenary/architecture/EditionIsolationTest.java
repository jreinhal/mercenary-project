package com.jreinhal.mercenary.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * ArchUnit tests to enforce SENTINEL edition isolation and layer boundaries.
 *
 * Edition isolation is primarily enforced at compile time via Gradle sourceSets exclusion.
 * These tests provide a second line of defense: even if a developer adds an import that
 * compiles in the government edition (which includes everything), ArchUnit will flag it
 * as a violation if it crosses an edition boundary that lower editions cannot satisfy.
 *
 * Layer boundary tests ensure service classes don't depend on controller classes
 * (proper dependency inversion).
 */
class EditionIsolationTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.jreinhal.mercenary");

    // ─── Edition Isolation ─────────────────────────────────────────

    @Test
    @DisplayName("core package must not depend on government package")
    void corePackageShouldNotDependOnGovernment() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..core..")
                .should().dependOnClassesThat()
                .resideInAPackage("..government..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("core package must not depend on medical package")
    void corePackageShouldNotDependOnMedical() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..core..")
                .should().dependOnClassesThat()
                .resideInAPackage("..medical..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("enterprise package must not depend on government package")
    void enterprisePackageShouldNotDependOnGovernment() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..enterprise..")
                .should().dependOnClassesThat()
                .resideInAPackage("..government..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("enterprise package must not depend on medical package")
    void enterprisePackageShouldNotDependOnMedical() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..enterprise..")
                .should().dependOnClassesThat()
                .resideInAPackage("..medical..");
        rule.check(CLASSES);
    }

    // ─── Layer Boundaries ──────────────────────────────────────────

    @Test
    @DisplayName("service layer must not depend on controller layer")
    void serviceLayerShouldNotDependOnControllerLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAPackage("..controller..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("RAG strategies must not depend on controller layer")
    void ragStrategiesShouldNotDependOnControllerLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..rag..")
                .should().dependOnClassesThat()
                .resideInAPackage("..controller..");
        rule.check(CLASSES);
    }

    @Test
    @DisplayName("vector store must not depend on controller layer")
    void vectorStoreShouldNotDependOnControllerLayer() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..vector..")
                .should().dependOnClassesThat()
                .resideInAPackage("..controller..");
        rule.check(CLASSES);
    }
}
