package com.ke.service.review;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 审计留痕标记（02 §9 / FR-O03）：标注在审核、状态流转、权限变更等写操作方法上，
 * {@link AuditAspect} 在方法成功返回后向 audit_log 写一行（action、object_type、object_id、
 * actor=SecurityContext 当前用户、detail_json=方法参数快照）。
 * <p>约定：object_id 解析顺序为①方法返回值是 Long；②参数上标了 {@link AuditId}。
 * 两者皆缺则跳过写入并告警（审计缺位可观察，业务不受影响）。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Audited {

    /** 审计动作名，如 CARD_PUBLISH / CARD_RETURN_DRAFT */
    String action();

    /** 被审计对象类型，如 CARD / ENTRY / REVIEW_TASK */
    String objectType();
}
