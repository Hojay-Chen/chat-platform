package com.luxera.companion.application.repository;

import com.luxera.companion.application.domain.OutboxEventRecord;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * {@code lap_outbox} 表的仓储 —— 名字里的 {@code Lap} 不是装饰。
 *
 * <p>Spring Data 给仓储起的 Bean 名是<em>类简名首字母小写</em>, 不是全限定名。平台核心里已经有一个
 * {@code com.luxera.companion.outbox.OutboxEventRepository}(数字人/聊天的 outbox), 两者**同名不同包**。
 * 各模块自己跑测试时只看得到一个, 编译期也毫无怨言 —— 但 {@code bootstrap-app} 是六个模块第一次
 * 同处一个 Spring 上下文的地方, 于是应用在启动时以
 * "The bean 'outboxEventRepository' ... has already been defined" 直接起不来。
 *
 * <p>这个坑值得写在这里: 它不会被任何一条模块边界守卫抓住(那套规则管的是包归属与 Maven 依赖,
 * 而它们全都是对的), 也不会被模块内的测试抓住 —— 只有整仓的 {@code mvn test} 走到 bootstrap 才会暴露。
 */
public interface LapOutboxRepository extends JpaRepository<OutboxEventRecord, String> {

    /** relay 的取件口: 最早落下的先投(保序不是必需的, 但可预测的投递顺序让排障容易得多)。 */
    List<OutboxEventRecord> findByStatusOrderByCreatedAtAsc(String status, Pageable page);

    long countByStatus(String status);
}
