package com.luxera.companion.contracts.client;

/**
 * V2.2 §6.3 第 9 项 —— "这个人是谁"。
 *
 * <h2>{@code displayName} 默认是空的, 而且这不是没实现</h2>
 *
 * <p>平台知道这个聊天账号的行, 但**不知道客户端心里管这个人叫什么**。设计文档 §4.3.7 说得
 * 很直接: "这个人叫什么"是客户端自己的知识, 聊天平台只给账号 id。对 Agent 而言, 这件事
 * 是它聊天时必须自己构建起来的关系网 —— 通讯录里第一条记录是创建 Agent 时就填好的那个
 * 用户(§6.5), 之后每一个陌生账号都先是"不认识的人", 聊过几次之后才形成印象。
 *
 * <p>所以向 Agent 一侧回答这个名字时, 它<em>总是</em> {@code null}: 平台没有一个"用户给
 * 自己起的备注"可以给它, 也不该把别人账号里的资料拼一个出来 —— 那会凭空替 Agent 造出
 * 一段它没经历过的社交关系。
 *
 * <p>这顺手也解决了一个隐私问题: 平台不需要向外部程序暴露用户的备注体系。
 *
 * @param accountId   聊天账号 id —— 这个类型里唯一恒定的字段
 * @param displayName 平台上这个账号的展示名。**向非真人主体回答时恒为 null**(见上)。
 * @param avatarUrl   头像。图床未接入, 恒为 null —— 但它留在这里是因为它同样是"平台知道的
 *                    东西", 与 displayName 属于同一类, 不是"以后可能加"的占位。
 */
public record ContactProfile(
        String accountId,
        String displayName,
        String avatarUrl
) {
}
