package com.luxera.companion.application.action;

import com.luxera.companion.application.manifest.ApplicationManifest;

/**
 * LAP v1: 一次"这是哪个应用的哪个动作"的解答。
 *
 * <p>两个字段一起才够用: manifest 给出<em>上下文</em>(资源模板、事件声明、运行时类型 ——
 * 后面的每一步都要它), {@code spec} 给出<em>这一次</em>的规格(权限级别、风险、输入 schema、
 * 给 Agent 的提示)。只返回其中一个都会让调用方回头再查一次, 而两次查询之间注册表可能变了。
 */
public record ActionResolution(ApplicationManifest manifest, ApplicationManifest.ActionDecl spec) {

    public String applicationId() {
        return manifest.applicationId();
    }

    public String version() {
        return manifest.version();
    }

    public String actionId() {
        return spec.id();
    }

    public ActionHandlerKey handlerKey() {
        return ActionHandlerKey.of(manifest.applicationId(), manifest.version(), spec.id());
    }
}
