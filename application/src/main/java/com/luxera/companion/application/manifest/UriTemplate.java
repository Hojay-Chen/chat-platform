package com.luxera.companion.application.manifest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * manifest 里 {@code resources[].uriTemplate} 的匹配器。
 *
 * <p>为什么不复用 {@code contracts.ResourceUriPattern}: 那个是<em>订阅过滤器</em>的语义 ——
 * 它只回答"我关不关心这个 URI", 模板里的 {@code {sessionId}} 会被当字面量。资源归属需要的是
 * "这个 target 属于哪个应用、参数是什么", 所以这里是一个小的 URI template 实现:
 *
 * <ul>
 *   <li>{@code {name}} 匹配恰好一段并捕获({@code {name}} 不匹配空段);</li>
 *   <li>{@code *} 匹配恰好一段, 不捕获;</li>
 *   <li>{@code **} 匹配余下全部(只在结尾有意义)。</li>
 * </ul>
 */
public final class UriTemplate {

    private UriTemplate() {
    }

    /** 模板是否匹配该 URI。 */
    public static boolean matches(String template, String uri) {
        return match(template, uri, null);
    }

    /** 捕获模板变量; 不匹配返回空。无变量的模板匹配时返回空 map, 用 {@link #matches} 判别。 */
    public static Map<String, String> extract(String template, String uri) {
        Map<String, String> out = new LinkedHashMap<>();
        return match(template, uri, out) ? out : Map.of();
    }

    /** 取单个变量, 如 {@code sessionId}。 */
    public static Optional<String> variable(String template, String uri, String name) {
        return Optional.ofNullable(extract(template, uri).get(name));
    }

    private static boolean match(String template, String uri, Map<String, String> captures) {
        if (template == null || uri == null) return false;
        String[] t = template.split("/", -1);
        String[] u = uri.split("/", -1);
        int i = 0;
        for (; i < t.length; i++) {
            String segment = t[i];
            if ("**".equals(segment)) return true;
            if (i >= u.length) return false;
            if ("*".equals(segment)) continue;
            if (segment.length() > 2 && segment.charAt(0) == '{' && segment.endsWith("}")) {
                if (u[i].isEmpty()) return false;
                if (captures != null) captures.put(segment.substring(1, segment.length() - 1), u[i]);
                continue;
            }
            if (!segment.equals(u[i])) return false;
        }
        return i == u.length;
    }
}
