// CHECK-V10-ALLOW-GUARD  本文件是边界守卫: 出现其他项目的包名是断言的素材, 不是依赖
package com.luxera.companion.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 仓 1 边界的结构性守卫 —— G1 物理拆分后改写。
 *
 * <p>拆分前的规则守着"chat / DH / application 三个 Maven 模块同进程互不引用";
 * 拆分后 DH 整个模块已迁往 simulation-agent-platform 仓库, **物理上不再共享
 * classpath** —— "chat 不依赖 DH" 从此由仓库边界保证, 不再需要 ArchUnit。
 *
 * <p>本测试改守拆分后仍然需要守的两件事:
 * <ol>
 *   <li><b>chat 与 application 同进程但边界仍在</b>: 两者只经 contracts 的端口
 *       ({@code ApplicationCatalogPort} 等)相见, 不许直接引用对方的实现类 ——
 *       否则有一天想把 application 拆成第二个进程时, 又要重新拆一遍。</li>
 *   <li><b>contract 仍然自足</b>: 它是两仓共享的对外契约, 任何对平台实现类的
 *       依赖都会让另一个仓库编译不过。</li>
 * </ol>
 *
 * <p>包名必须写全限定前缀({@code com.luxera.companion.conversation..} 而不是
 * {@code ..conversation..}) —— 通配写法会把 contracts.chat 一起吞掉, 规则立刻
 * 变成假阳性, 最后只能被删掉: 一个看起来在守边界、其实什么都守不住的空壳。
 */
class ModuleBoundaryArchitectureTest {

    /** chat 拥有的顶层包 */
    private static final String[] CHAT_PLATFORM_PACKAGES = {
            "com.luxera.companion.conversation..", "com.luxera.companion.event..",
            "com.luxera.companion.simulator..",
    };

    /** application(LAP) 拥有的顶层包 */
    private static final String[] APPLICATION_PLATFORM_PACKAGES = {
            "com.luxera.companion.application..",
    };

    private static JavaClasses classes;

    @BeforeAll
    static void setup() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    /** chat 见 application 只能经 contracts 端口, 不许碰实现类。 */
    @Test
    void chat_platform_does_not_depend_on_application_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }

    /** 反方向同理: application 是可独立嵌入的宿主, 不许认识聊天。 */
    @Test
    void application_platform_does_not_depend_on_chat_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES)
                .should().dependOnClassesThat().resideInAnyPackage(CHAT_PLATFORM_PACKAGES);
        rule.check(classes);
    }

    /**
     * contract 是两仓共享的对外契约(本仓 publishToMavenLocal, simulation-agent-platform
     * 当外部依赖引入) —— 它依赖任何一个平台实现类, 另一个仓库的编译就会断。
     */
    @Test
    void contracts_depend_on_no_platform() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.luxera.companion.contracts..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .orShould().dependOnClassesThat()
                .resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }

    /**
     * common(原 kernel)是仓内公共件, 不是对外契约 —— 但它同样不许认识两个平台:
     * 它给两边提供 auth/outbox 等底座, 认识任何一边就成了那一边的私有物。
     */
    @Test
    void common_depends_on_no_platform() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        "com.luxera.companion.auth..", "com.luxera.companion.config..",
                        "com.luxera.companion.outbox..", "com.luxera.companion.common..")
                .should().dependOnClassesThat()
                .resideInAnyPackage(CHAT_PLATFORM_PACKAGES)
                .orShould().dependOnClassesThat()
                .resideInAnyPackage(APPLICATION_PLATFORM_PACKAGES);
        rule.check(classes);
    }
}
