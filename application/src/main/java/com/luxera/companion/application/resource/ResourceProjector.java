package com.luxera.companion.application.resource;

import com.luxera.companion.contracts.application.ResourceView;

import java.util.Optional;

/**
 * LAP v1: {@code backing: APP_OWNED} 资源的读取出口。
 *
 * <p>绝大多数资源直接存在 {@code resource.state_json} 里({@code RESOURCE_STORE}), 读就是读一行。
 * 但有一类资源——提醒是第一个——状态天然长在自己那张表上, 而且必须能被 SQL 索引:
 * "找出所有 {@code status='PENDING' AND due_at <= now()} 的提醒"这件事 JSON 列做不到。
 *
 * <p>于是这个接口: 应用自己有一张真表, 平台在读的时候把它<em>投影</em>成一个
 * {@code ResourceView}。<b>关键是没有双写</b> —— {@code reminder_item} 是唯一写入点,
 * {@code resource} 表里根本没有对应的行, 也就不存在"两张表谁是真理"的问题。
 *
 * <p>写路径不走这里: {@code APP_OWNED} 资源由应用的 handler 自己写自己的表, 平台不代劳。
 * 换句话说, 投影是单向的(表 → 视图), 这也是它不会漂移的原因。
 */
public interface ResourceProjector {

    /** 这个投影器是否认得这个 URI。多个投影器时按声明顺序问第一个认得的。 */
    boolean supports(String uri);

    /** 投影出视图; URI 合法但当前不存在时返回空。 */
    Optional<ResourceView> project(String uri);
}
