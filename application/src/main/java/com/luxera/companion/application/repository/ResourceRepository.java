package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.ResourceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ResourceRepository extends JpaRepository<ResourceRecord, String> {

    Optional<ResourceRecord> findByUri(String uri);

    List<ResourceRecord> findBySessionId(String sessionId);

    List<ResourceRecord> findByApplicationId(String applicationId);

    /**
     * 乐观并发的唯一闸门。<b>绝不无条件 UPDATE。</b>
     *
     * <p>返回 0 表示"我读到的版本已经不是当前版本了" —— 调用方必须把它翻成
     * {@code STATE_CONFLICT} 并把当前版本带回去, 让对家能立即重读重试,
     * 而不是拿到一个失败就放弃(那会让"两个 principal 抢同一步棋"变成随机丢子)。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ResourceRecord r set r.stateJson = :state, r.stateVersion = r.stateVersion + 1, "
            + "r.updatedAt = :now where r.uri = :uri and r.stateVersion = :expected")
    int compareAndSet(@Param("uri") String uri,
                      @Param("state") String state,
                      @Param("expected") long expected,
                      @Param("now") LocalDateTime now);

    /**
     * 把资源行重新挂到另一个会话上, <b>且不动状态与版本</b>。
     *
     * <p>单独一条语句而不是并进 {@link #compareAndSet}: 那条是乐观并发的闸门, 它的返回值承载着
     * "我读到的是不是当前版本"这个判断。把一次记账性的改动混进去, 要么让 CAS 多一个失败理由,
     * 要么让调用方分不清"冲突"与"换了个会话"。这里不需要 CAS —— 重挂的目标由平台的会话解析
     * 决定, 不存在两个调用方争抢同一个锚的情形。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ResourceRecord r set r.sessionId = :sessionId where r.uri = :uri")
    int reanchor(@Param("uri") String uri, @Param("sessionId") String sessionId);
}
