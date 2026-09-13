package com.luxera.companion.application.manifest;

/**
 * LAP v1 §Manifest: 应用代码跑在哪里。
 *
 * <ul>
 *   <li>{@link #NATIVE} —— 进程内, 由本平台的 handler 直接执行。参考应用都是这一类。</li>
 *   <li>{@link #REMOTE} —— 应用自己部署在别处, 由 {@code RemoteApplicationInvoker} 转发
 *       。manifest 里只声明一个规范 endpoint, 且 {@code authRef} 是**名字**不是密钥。</li>
 *   <li>{@link #HOSTED} —— 平台替应用跑代码(JVM sandbox / WASM)。<b>本阶段明确不做</b>,
 *       validator 对它显式报错 {@code RUNTIME_TYPE_NOT_SUPPORTED}, 而不是静默接受。</li>
 * </ul>
 */
public enum RuntimeType {
    NATIVE,
    REMOTE,
    HOSTED
}
