package com.luxera.companion.contracts.spi;

import com.luxera.companion.contracts.application.PrincipalType;

import java.util.Optional;

/**
 * 读一个平台令牌: <b>"这个 Bearer 令牌是谁的、他是什么类型的 principal"</b>。
 *
 * <p><b>为什么这是一个端口而不是一次直接调用。</b>令牌的签发与校验属于 {@code platform-kernel}
 * —— 密钥、算法、过期规则都在那里, 那是唯一一份实现。而 {@code application-platform} 需要知道
 * "这个令牌对应谁" 才能做权限判定。让它直接 import kernel 里的 {@code JwtUtil} 是最短的
 * 路, 但会把整个 kernel 拖进应用平台的 classpath: 用户表、Spring Security 过滤链、
 * 全局异常处理 —— 以及一个模块测试再也起不来的独立上下文。
 *
 * <p>所以这里放一个只回答那一个问题的端口。kernel 实现它, 应用平台消费它, 两边都只依赖
 * {@code contracts}。签名算法或密钥轮换时改的是 kernel 里的实现, 不是这里的接口。
 *
 * <p><b>不返回 {@code null} 也不抛异常。</b>令牌无效是<em>正常</em>的输入 —— 过期、伪造、
 * 别的系统签的都算, 调用方要的答案是"不行", 不是一个需要 try/catch 的意外。
 */
public interface PrincipalTokenReader {

    /** 读得出身份就返回它, 否则返回空。空 Token 同样返回空, 不抛异常。 */
    Optional<Identity> read(String token);

    /**
     * 令牌里的身份。
     *
     * @param principalId   主体 id(真人即 userId, Agent 即 companionId)
     * @param principalType 令牌声明的 principal 类型。老令牌缺这个 claim 时由实现方回落
     *                      {@link PrincipalType#HUMAN} —— 那是<em>历史兼容</em>, 不是通用默认值:
     *                      Agent 侧的身份不经过令牌, 由 {@code InternalPrincipalResolver} 强制显式声明。
     */
    record Identity(String principalId, PrincipalType principalType) {
    }
}
