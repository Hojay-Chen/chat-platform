// CHECK-V10-ALLOW-GUARD  本文件是边界守卫: 出现其他项目的包名是断言的素材, 不是依赖
package com.luxera.companion.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 六个模块里**不许有两个同简名的 Bean**。
 *
 * <p>Spring 给组件起的默认 Bean 名是<em>类简名首字母小写</em>, 不是全限定名 —— 于是
 * {@code com.luxera.companion.outbox.OutboxEventRepository} 与
 {@code com.luxera.companion.application.repository.OutboxEventRepository} 抢同一个
 * {@code outboxEventRepository}, 而 Spring Boot 2.1 起**默认禁止覆盖**, 结果是
 * {@code bootstrap-app} 起不来:
 *
 * <pre>
 * The bean 'outboxEventRepository' ... has already been defined in
 * com.luxera.companion.application.repository.OutboxEventRepository
 * </pre>
 *
 * <p><b>为什么这条规则非得存在。</b>这个坑在每一个模块自己的测试里都是隐形的 —— 只看得到一个类,
 * 编译期也毫无怨言; 各模块的包归属与 Maven 依赖全都正确, 所以 {@code check-v10.sh} 那十条边界守卫
 * 一条都不会响。它是"六个模块第一次同处一个 Spring 上下文"才存在的失败模式, 而那个地方只有
 * {@code bootstrap-app} 是。同理, ArchUnit 依赖分析也抓不到它: 类型不同、没有依赖关系,
 * 唯一冲突的东西是**一个字符串**。
 *
 * <p>规则覆盖面刻意比"带注解的类"宽一层: **Spring Data 仓储接口是没有注解的**(它们靠
 * 继承 {@code Repository} 标记接口被扫描到), 只扫 {@code @Component} 一族会正好漏掉这次的真凶。
 */
class BeanNameCollisionArchitectureTest {

    /** 会被组件扫描注册成 Bean 的注解 —— @Configuration 也在内(它同样是 @Component)。 */
    private static final Set<String> STEREOTYPES = Set.of(
            "org.springframework.stereotype.Component",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.context.annotation.Configuration",
            "org.springframework.boot.context.properties.ConfigurationProperties",
            "org.springframework.data.repository.RepositoryDefinition");

    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";

    private static JavaClasses classes;

    @BeforeAll
    static void setup() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.luxera.companion");
    }

    @Test
    void noTwoBeanClassesShareASimpleName() {
        Map<String, List<String>> byBeanName = new TreeMap<>();
        for (JavaClass javaClass : classes) {
            if (javaClass.getName().contains("$")) {
                continue;   // 内部类: Spring 用的是 "outer.Inner" 这种带前缀的名字, 撞不上
            }
            if (!isBean(javaClass)) {
                continue;
            }
            byBeanName.computeIfAbsent(beanName(javaClass), k -> new java.util.ArrayList<>())
                    .add(javaClass.getName());
        }

        Map<String, List<String>> collisions = byBeanName.entrySet().stream()
                .filter(e -> e.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));

        assertTrue(collisions.isEmpty(),
                "Bean 名撞车 —— 应用会在 bootstrap-app 启动时直接失败(Spring Boot 默认禁止覆盖):\n"
                        + collisions.entrySet().stream()
                        .map(e -> "  " + e.getKey() + " ← " + e.getValue())
                        .collect(Collectors.joining("\n"))
                        + "\n改名(比如给其中一个加上它所属平台的前缀), 不要打开 allow-bean-definition-overriding。");

        // 一条"什么都没扫到"的规则会永远通过, 比不写更糟 —— 所以顺带钉住这条规则确实有覆盖面。
        // G1 拆分后仓 1 的 Bean 面 ≈ 150 个（chat+application+common; 原全量 800+ 的一半在 DH 仓）。
        assertTrue(byBeanName.size() > 100,
                "只扫到 " + byBeanName.size() + " 个 Bean —— 导入范围或判定逻辑坏了, 这条规则已经形同虚设");
        assertTrue(byBeanName.values().stream().anyMatch(v -> v.get(0).contains(".repository.")),
                "一个 Spring Data 仓储都没扫到 —— 而它正是这条规则存在的理由");
    }

    /**
     * {@code @Component} 一族**或** Spring Data 仓储接口。后者没有注解, 靠继承
     * {@code Repository} 这个空标记接口被扫描到, 所以只能从类型层次上认。
     */
    private static boolean isBean(JavaClass javaClass) {
        if (javaClass.getAnnotations().stream()
                .anyMatch(a -> STEREOTYPES.contains(a.getRawType().getName()))) {
            return true;
        }
        return javaClass.isInterface() && javaClass.getAllRawInterfaces().stream()
                .anyMatch(i -> SPRING_DATA_REPOSITORY.equals(i.getName()));
    }

    /** Spring 的 {@code AnnotationBeanNameGenerator} 用的就是这一条: 简名首字母小写。 */
    private static String beanName(JavaClass javaClass) {
        String simple = javaClass.getSimpleName();
        return Character.toLowerCase(simple.charAt(0)) + simple.substring(1);
    }
}
