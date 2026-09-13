package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ApplicationSessionRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ApplicationSessionRepository extends JpaRepository<ApplicationSessionRecord, String> {

    List<ApplicationSessionRecord> findByApplicationId(String applicationId);

    /**
     * §85 —— 这段对话里开着的全部会话。
     *
     * <p>{@code conversation_id} 可空(不是每一条会话都从对话里开出来), 于是"这段对话"
     * 永远不是"所有会话" —— 这正是把列放在会话行上而不是另建一张关联表的意义: 空值天然地
     * 把"不在对话里"的那些排除掉, 不需要一个额外的判据。
     */
    List<ApplicationSessionRecord> findByConversationId(String conversationId);

    /** 回收用: 长期没有动作的会话。 */
    List<ApplicationSessionRecord> findByStatusAndLastActiveAtBefore(String status, LocalDateTime before);

    /** 会话解析第 4 档: 这个 principal 开的、在这个应用下最近的活跃会话。 */
    Optional<ApplicationSessionRecord> findFirstByOwnerPrincipalTypeAndOwnerPrincipalIdAndApplicationIdAndStatusOrderByLastActiveAtDesc(
            String ownerPrincipalType, String ownerPrincipalId, String applicationId, String status);
}
