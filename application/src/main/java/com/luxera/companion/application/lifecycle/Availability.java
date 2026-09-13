package com.luxera.companion.application.lifecycle;

import com.luxera.companion.application.domain.ApplicationStatus;

import java.util.List;

/**
 * LAP v2 §4.1: <b>一个应用此刻"能不能用"</b> —— 五态, 三列, 一张表。
 *
 * <p>R8 的十态状态机管的是"应用怎么走完从草稿到下架的一生": 每一步都是流程与权柄
 * (谁能推、下一步该是哪个)。这一层回答的是完全不同的一组问题, 而且是<em>使用者</em>问的:
 *
 * <pre>
 *   它出现在应用市场里吗?      inMarket()
 *   我能为它开一个新会话吗?    allowsNewSession()
 *   我手上那个会话还能用吗?    allowsExistingSession()
 * </pre>
 *
 * <p><b>五态是十态的投影, 不是它的替代。</b> 十态仍然是唯一的真相
 * ({@code application.status} / {@code application_version.status}), 这里给的是它落在这三个
 * 问题上的<em>行为等价类</em> —— 于是"投影"这个词是认真的: {@link #of(ApplicationStatus)}
 * 会把不同的状态映到同一个值上, 而且刻意如此。
 *
 * <pre>
 *   DRAFT       ← DRAFT, DEVELOPING, TESTING, REJECTED     作者手里, 还没交出去
 *   REVIEWING   ← SUBMITTED, REVIEWING, APPROVED           在流程里, 还没上架
 *   PUBLISHED   ← PUBLISHED                                在架上
 *   SUSPENDED   ← SUSPENDED                                被运营拿下架, 但场上还有人在玩
 *   DEPRECATED  ← DEPRECATED                               不再维护, 已有会话走完最后一段
 * </pre>
 *
 * <p><b>为什么 TESTING 归 DRAFT、APPROVED 归 REVIEWING。</b> 因为这五个值回答的不是
 * "走到哪一步了"(那是十态的事), 而是"<em>能不能用</em>"。自测中的和刚被驳回的, 对使用者
 * 是同一件事: 看不见、开不了、也没有。过了审但还没按发布键的, 与正在审的, 对使用者也
 * 是同一件事。把这两个分别拆出去只会多出两个在三列上完全一样的行 —— 而一份有重复行的表,
 * 迟早会有人在其中一行上改错。
 *
 * <p><b>代价是名字丢了</b>: 投影之后说得出"不可用", 说不出"因为被驳回"。所以它只用在
 * 准入判断与市场列表上; 要把状态<em>讲给人听</em>的地方(开发者后台、审核队列)读十态原值。
 *
 * <p><b>{@code DEPRECATED} 与 {@code SUSPENDED} 在三列上完全相同</b>, 这是计划里明确
 * 定下的一条(方案 §4.1 给 DEPRECATED 的"是否允许已有 Session"写的是"视策略"——
 * 而"视策略"不是一条可实现的规定)。定成一样, 是因为它们要的是同一件事: <em>不许再开新的,
 * 但场上那局让人下完。</em> 一局下到一半的棋因为应用被下架而当场作废, 是平台在惩罚用户
 * 承担运营的后果。
 */
public enum Availability {

    /** 作者手里, 还没交出去。市场看不见, 会话开不了 —— 也不该有人有。 */
    DRAFT(false, false, false),

    /** 交出去在流程里(含过了审但还没发布)。同样不可见、不可开。 */
    REVIEWING(false, false, false),

    /** 在架上。唯一的"三列全 yes"。 */
    PUBLISHED(true, true, true),

    /** 被拿下架。新会话停止, 场上已有的照常。 */
    SUSPENDED(false, false, true),

    /** 不再维护。与 {@link #SUSPENDED} 同 —— 见类注释。 */
    DEPRECATED(false, false, true);

    private final boolean inMarket;
    private final boolean allowsNewSession;
    private final boolean allowsExistingSession;

    Availability(boolean inMarket, boolean allowsNewSession, boolean allowsExistingSession) {
        this.inMarket = inMarket;
        this.allowsNewSession = allowsNewSession;
        this.allowsExistingSession = allowsExistingSession;
    }

    /** 是否出现在应用市场 / 发现链里。 */
    public boolean inMarket() {
        return inMarket;
    }

    /** 是否允许为它<em>新开</em>一个会话。 */
    public boolean allowsNewSession() {
        return allowsNewSession;
    }

    /** 是否允许继续使用<em>已经存在</em>的会话。 */
    public boolean allowsExistingSession() {
        return allowsExistingSession;
    }

    /**
     * 十态 → 五态。未知/空值一律落到 {@link #DRAFT} —— 一个认不出来的状态
     * <b>绝不能</b>被当成"在架", 那是把配置错误放大成一次未经审核的发布。
     */
    public static Availability of(ApplicationStatus status) {
        if (status == null) {
            return DRAFT;
        }
        return switch (status) {
            case DRAFT, DEVELOPING, TESTING, REJECTED -> DRAFT;
            case SUBMITTED, REVIEWING, APPROVED -> REVIEWING;
            case PUBLISHED -> PUBLISHED;
            case SUSPENDED -> SUSPENDED;
            case DEPRECATED -> DEPRECATED;
        };
    }

    /**
     * 十态里每一个落在哪一行, <b>由本枚举给出而不是另写一张表</b> —— 与
     * {@code ApplicationStatus.legalSuccessorsOf} 同一条理由: 两张表迟早有一张忘了改,
     * 而那种偏差的表现是"某个状态在这一层莫名可用"。R15 的守卫拿它逐行断言。
     */
    public static List<ApplicationStatus> sourcesOf(Availability availability) {
        return java.util.Arrays.stream(ApplicationStatus.values())
                .filter(s -> of(s) == availability)
                .toList();
    }
}
