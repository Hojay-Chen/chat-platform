package com.luxera.companion.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface EventLogRepository extends JpaRepository<EventLogEntry, Long> {

    /** 游标回放: 取 afterId 之后的全部事件(按 id 升序) */
    List<EventLogEntry> findByCompanionIdAndIdGreaterThanOrderByIdAsc(String companionId, long afterId);

    /** 最近 N 条(新连接无游标时可选回放最近若干条) */
    @Query("select e from EventLogEntry e where e.companionId = :companionId order by e.id desc")
    List<EventLogEntry> findRecent(@Param("companionId") String companionId, org.springframework.data.domain.Pageable pageable);
}
