package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.SessionPermissionRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SessionPermissionRepository extends JpaRepository<SessionPermissionRecord, String> {

    List<SessionPermissionRecord> findByParticipantId(String participantId);

    /**
     * 移除参与者时连带清掉它的授权。
     *
     * <p>为什么不是"留着但标记失效": 授权的作用域是 {@code participant_id}, 而参与者离开之后
     * 那一行仍在(唯一键要求它留着, 否则重进会撞键)。留着一批指向"已经不在场的人"的授权行,
     * 只会让下一个人读到它时以为它还生效。要重进就重新铺一遍 —— profile 是确定的, 重建没有代价。
     */
    @Modifying
    @Query("delete from SessionPermissionRecord p where p.participantId = :participantId")
    void deleteByParticipantId(@Param("participantId") String participantId);

    /** 端点上的"这个人在这局里有哪些能力" —— 与 {@code PermissionEvaluator} 用的是同一批行。 */
    @Query("select distinct p.capabilityId from SessionPermissionRecord p "
            + "where p.participantId = :participantId and p.actionId is null and p.capabilityId is not null")
    List<String> capabilityIdsOf(@Param("participantId") String participantId);
}
