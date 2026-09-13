package com.luxera.companion.contracts.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * contracts 模块自身的架构约束: 它是三个平台共用的"语言", 必须保持自足。
 *
 * <p>这个模块是唯一没有平台代码在 classpath 上的模块 —— 所以这里能检查的"没有跨模块依赖"
 * 是结构性的: 一旦有人往 contracts 的 pom 里加了 chat/DH/kernel 的依赖, 这些类就会出现在
 * classpath 上, 下面的规则立刻失败。
 *
 * <p>真正的跨模块方向约束(chat 不得引用 DH 的包, 反之亦然)需要同时看得见所有模块,
 * 所以放在 bootstrap-app 的 {@code ModuleBoundaryArchitectureTest}。
 * grep 版快速守卫在同级 {@code scripts/check-v10.sh}。
 */
class ArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setup() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    @Test
    void contracts_only_depend_on_themselves_and_libraries() {
        // "只能依赖自己 + 第三方库": 比"不得依赖某几个包"更强 —— 后者总会漏掉某个包,
        // 而白名单一旦有人往 contracts 里塞平台代码(比如让 MessageView 认识 JPA Entity)就会失败。
        ArchRule selfContained = classes()
                .that().resideInAPackage("com.luxera.companion.contracts..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(
                        "com.luxera.companion.contracts..",
                        "java..",
                        "javax..",
                        "org.springframework..",
                        "com.fasterxml.jackson..",
                        "lombok.."
                );
        selfContained.check(importedClasses);
    }

    @Test
    void contracts_have_no_cycles() {
        ArchRule noCycles = slices()
                .matching("com.luxera.companion.contracts.(*)..")
                .should().beFreeOfCycles();
        noCycles.check(importedClasses);
    }

    @Test
    void contracts_do_not_throw_generic_exceptions() {
        NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS.check(importedClasses);
    }
}
