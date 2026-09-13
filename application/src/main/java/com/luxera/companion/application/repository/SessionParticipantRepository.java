package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.SessionParticipantRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * LAP v2: 参与者表 —— 权限模型的第一维, 也是事件路由找数字人的唯一依据。
 *
 * <p>查询方法刻意都是"会话内"的: 参与者离开会话就没有意义, 所以本表上不存在
 * "按 principal 横跨所有会话"的便捷方法。想跨会话找, 得先有会话 —— 这从接口形状上
 * 就挡住了"某个 Agent 全局拥有某个应用的权限"这种 v1 才有的想法。
 */
public interface SessionParticipantRepository extends JpaRepository<SessionParticipantRecord, String> {

    Optional<SessionParticipantRecord> findBySessionIdAndPrincipalTypeAndPrincipalId(
            String sessionId, String principalType, String principalId);

    List<SessionParticipantRecord> findBySessionId(String sessionId);

    List<SessionParticipantRecord> findBySessionIdAndStatus(String sessionId, String status);

    /** 事件路由用: 这个会话里活跃的某一类参与者(AGENT)。 */
    List<SessionParticipantRecord> findBySessionIdAndPrincipalTypeAndStatus(
            String sessionId, String principalType, String status);

    /** "我在哪些会话里" —— 会话解析第 4 档要找的就是它, 所以必须带 status。 */
    List<SessionParticipantRecord> findByPrincipalTypeAndPrincipalIdAndStatus(
            String principalType, String principalId, String status);

    long countBySessionIdAndStatus(String sessionId, String status);
}
