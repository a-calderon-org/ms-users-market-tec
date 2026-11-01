package com.markettec.users.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.markettec.users", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule dependenciesPointInward = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain").definedBy("..domain..")
            .layer("Application").definedBy("..application..")
            .layer("Adapters").definedBy("..adapters..")
            .layer("Configuration").definedBy("..config..")
            .whereLayer("Domain").mayNotAccessAnyLayer()
            .whereLayer("Application").mayOnlyAccessLayers("Domain")
            .whereLayer("Adapters").mayOnlyAccessLayers("Application", "Domain")
            .whereLayer("Configuration").mayOnlyAccessLayers("Application", "Adapters", "Domain");

    @ArchTest
    static final ArchRule domainIsFrameworkIndependent = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.google..",
                    "com.azure..",
                    "org.hibernate..",
                    "..application..",
                    "..adapters..",
                    "..config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnOuterLayers = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.google..",
                    "com.azure..",
                    "org.hibernate..",
                    "..adapters..",
                    "..config..");

    @ArchTest
    static final ArchRule topLevelPackagesAreFreeOfCycles = slices()
            .matching("com.markettec.users.(*)..")
            .should().beFreeOfCycles();
}
