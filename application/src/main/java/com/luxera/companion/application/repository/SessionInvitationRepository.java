package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.SessionInvitationRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * LAP v2: 邀请表 —— "一张票"的存取。
 *
 * <p>与参与者表一样, 查询刻意都是<em>会话内</em>的: 一张票离开了它邀请的会话就没有意义。
 * 唯一的"会话外"裸查 {@code findByTokenHash} 是公开加入端点({@code POST /join/{token}})的
 * 入口 —— 它拿到的只是一段随机串, 不存在"泄露了谁的什么"的可能, 那正是 Capability Token
 * 与数据库 id 的分界。
 */
public interface SessionInvitationRepository extends JpaRepository<SessionInvitationRecord, String> {

    /** 公开加入端点的唯一入口 —— 拿到随机串, 查到票。 */
    Optional<SessionInvitationRecord> findByTokenHash(String tokenHash);

    List<SessionInvitationRecord> findBySessionId(String sessionId);

    /** 会话里还能用的票(none expired by clock)。"能用"= CREATED 且没到点。 */
    @org.springframework.data.jpa.repository.Query(
            "select i from SessionInvitationRecord i where i.sessionId = :sessionId "
                    + "and i.status = 'CREATED' and (i.expiresAt is null or i.expiresAt > current_timestamp)")
    List<SessionInvitationRecord> findUsableBySessionId(
            @org.springframework.data.repository.query.Param("sessionId") String sessionId);
}