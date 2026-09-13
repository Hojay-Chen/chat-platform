package com.luxera.companion.application.action;

import com.fasterxml.jackson.databind.JsonNode;
import com.luxera.companion.contracts.application.ResourceView;

/**
 * 处理器读写 Resource 的唯一入口(由 R4c 的 {@code ResourceStore} 实现)。
 *
 * <p>它是接口而不是直接给 {@code ResourceStore}: 处理器只该看见"读一个 URI / 按版本写一个
 * URI", 不该看见事务、CAS 重试、投影器这些宿主的事。测试里也就能塞一个内存实现。
 *
 * <p>{@link #write} <b>一律 CAS</b>, 不做无条件 UPDATE —— 两个 principal 同时操作同一
 * Resource 时, 输的那个拿到干净的 {@code STATE_CONFLICT} 而不是悄悄丢掉一次落子。
 */
public interface ResourceAccess {

    /** 当前状态; 不存在返回 {@code null}(而不是抛异常 —— "还没有"是正常状态)。 */
    ResourceView read(String uri);

    /**
     * 写入状态并令 {@code state_version + 1}。
     *
     * @param expectedVersion 期望的当前版本; 为 {@code null} 时在同一事务内先读一次再 CAS
     * @throws com.luxera.companion.application.resource.StateConflictException 版本不匹配
     */
    ResourceView write(String uri, String resourceType, JsonNode state, Long expectedVersion);
}
