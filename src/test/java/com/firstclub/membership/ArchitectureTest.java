package com.firstclub.membership;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces layering as plain (non-Spring) tests over the compiled bytecode — fast and
 * Docker-free. Keeps the architecture from eroding as the codebase grows.
 */
@DisplayName("Architecture — layering rules")
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.firstclub.membership");
    }

    @Test @DisplayName("controllers must not depend on repositories directly")
    void controllersDoNotUseRepositories() {
        ArchRule rule = noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..");
        rule.check(classes);
    }

    @Test @DisplayName("services must not depend on controllers")
    void servicesDoNotDependOnControllers() {
        ArchRule rule = noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..controller..");
        rule.check(classes);
    }

    @Test @DisplayName("entities must not depend on services or controllers")
    void entitiesAreStandalone() {
        ArchRule rule = noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..");
        rule.check(classes);
    }

    @Test @DisplayName("repositories live in the repository package and are interfaces")
    void repositoriesAreInterfaces() {
        ArchRule rule = classes().that().haveSimpleNameEndingWith("Repository")
                .and().resideInAPackage("..repository..")
                .should().beInterfaces();
        rule.check(classes);
    }

    @Test @DisplayName("controllers are annotated @RestController and live in the controller package")
    void controllersAreAnnotated() {
        ArchRule rule = classes().that().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .andShould().resideInAPackage("..controller..");
        rule.check(classes);
    }
}
