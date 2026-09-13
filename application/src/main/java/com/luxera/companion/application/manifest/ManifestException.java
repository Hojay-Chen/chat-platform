package com.luxera.companion.application.manifest;

/**
 * manifest 解析 / 校验失败。<b>一定带错误码</b> —— 发布失败时开发者看到的是
 * {@code EVENT_ID_TEMPLATE_REQUIRED} 这样的定位, 而不是一句"manifest 不合法"。
 */
public class ManifestException extends RuntimeException {

    private final String code;

    public ManifestException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static ManifestException of(String code, String message) {
        return new ManifestException(code, message);
    }
}
