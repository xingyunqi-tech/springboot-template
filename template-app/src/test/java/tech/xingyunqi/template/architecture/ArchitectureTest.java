package tech.xingyunqi.template.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.conditions.ArchConditions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

@AnalyzeClasses(packages = "tech.xingyunqi.template", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule bannedLibraries =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("org.dromara..", "cn.hutool..", "cn.dev33..");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION.allowEmptyShould(true);

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noValueInjection = noFields()
            .should()
            .beAnnotatedWith(Value.class)
            .as("配置只通过 @ConfigurationProperties 读取")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noValueParameters = noCodeUnits()
            .should(ArchConditions.have(DescribedPredicate.describe(
                    "parameters annotated with @Value",
                    (JavaCodeUnit unit) -> unit.getParameters().stream()
                            .anyMatch(parameter -> parameter.isAnnotatedWith(Value.class)))))
            .as("配置只通过 @ConfigurationProperties 读取")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule noEnvironmentLookup = noClasses()
            .should()
            .callMethod(System.class, "getenv", String.class)
            .orShould()
            .callMethod(System.class, "getenv")
            .as("配置只通过 @ConfigurationProperties 读取");

    @ArchTest
    static final ArchRule noTransactionalOnClasses = noClasses()
            .that()
            .areNotAnnotations()
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith("jakarta.transaction.Transactional")
            .as("业务代码只使用 @WriteTransaction");

    @ArchTest
    static final ArchRule noTransactionalOnMethods = noMethods()
            .should()
            .beAnnotatedWith(Transactional.class)
            .orShould()
            .beAnnotatedWith("jakarta.transaction.Transactional")
            .as("业务代码只使用 @WriteTransaction");

    @ArchTest
    static final ArchRule domainIsPure = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.baomidou..",
                    "org.apache.ibatis..",
                    "org.mybatis..",
                    "org.springframework.web..",
                    "org.springframework.http..",
                    "tools.jackson..",
                    "com.fasterxml.jackson..",
                    "jakarta.servlet..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule webDoesNotUsePersistence = noClasses()
            .that()
            .resideInAPackage("..web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..persistence..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule servicesDoNotDependOnWebOrContract = noClasses()
            .that()
            .resideInAnyPackage("tech.xingyunqi.template.core..", "tech.xingyunqi.template.modules..")
            .and()
            .resideOutsideOfPackage("..web..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..web..", "tech.xingyunqi.template.contract..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule contractOnlyUsedByWeb = noClasses()
            .that()
            .resideOutsideOfPackages("..web..", "tech.xingyunqi.template.contract..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("tech.xingyunqi.template.contract..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule contractHasNoBeanValidation = noClasses()
            .that()
            .resideInAPackage("tech.xingyunqi.template.contract..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("jakarta.validation..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule coreModulesFreeOfCycles = slices().matching("tech.xingyunqi.template.core.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule businessModulesFreeOfCycles = slices().matching("tech.xingyunqi.template.modules.(*)..")
            .should()
            .beFreeOfCycles()
            .allowEmptyShould(true);
}
