package com.ke.service.review;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * {@link Audited} 方法的对象 id 参数标记：方法返回值不是 Long 时，
 * {@link AuditAspect} 取该参数值（Number）作为 audit_log.object_id。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditId {
}
