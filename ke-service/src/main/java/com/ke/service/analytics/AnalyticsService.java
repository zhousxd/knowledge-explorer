package com.ke.service.analytics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.AnalyticsEventEntity;
import com.ke.infra.mapper.AnalyticsMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 埋点写入（FR-O05 / 01 §5，Task 33）：{@link #track} 由各域服务在业务动作完成处一行调用，
 * 事件类型取值域见 {@link AnalyticsEvents}。
 *
 * <p><b>写入策略（Task 33 决策：同步插入）</b>：单行 INSERT 成本低（索引仅 (event_type, created_at)），
 * 与业务同事务/同线程落库——换 @Async 异步要引入队列与丢事件窗口（进程退出/队列溢出），指标口径
 * 反而不稳，MVP 不做；若后续出现写入热点再升级异步批量。analytics 自身任何异常（序列化/DB 故障）
 * 一律 try/catch 记日志吞掉——<b>埋点不阻断业务</b>（业务成功优先于指标完整）。
 *
 * <p>payload 形状自由（JSONB），各事件的关键键名约定见 {@link AnalyticsEvents}；6 指标依赖的键：
 * node_visit.isNewKnowledge、service_run.{status,sessionId}。
 */
@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final AnalyticsMapper events;
    private final ObjectMapper objectMapper;

    public AnalyticsService(AnalyticsMapper events, ObjectMapper objectMapper) {
        this.events = events;
        this.objectMapper = objectMapper;
    }

    /**
     * 记一行事件。userId 可空（匿名动作，如免登录分享浏览）；payload 可空（纯计数事件）。
     * 恒不抛：失败仅记 warn（traceId 已由 MDC 之外的响应头链路承载，指标缺一行可容忍）。
     */
    public void track(Long userId, String eventType, Map<String, Object> payload) {
        try {
            AnalyticsEventEntity event = new AnalyticsEventEntity();
            event.setUserId(userId);
            event.setEventType(eventType);
            event.setPayload(payload == null ? null : objectMapper.writeValueAsString(payload));
            events.insert(event);
        } catch (JsonProcessingException e) {
            log.warn("analytics payload 序列化失败 eventType={} : {}", eventType, e.getMessage());
        } catch (Exception e) {
            log.warn("analytics 落库失败 eventType={} : {}", eventType, e.getMessage());
        }
    }

    /**
     * 埋点 payload 小工厂：k/v 成对收集、允许 null 值（{@code Map.of} 不允许 null），
     * 插入序保持调用序（payload::text 断言友好），让埋点调用处保持一行可读。
     */
    public static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
