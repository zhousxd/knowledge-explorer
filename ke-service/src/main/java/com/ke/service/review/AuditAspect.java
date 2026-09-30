package com.ke.service.review;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.infra.entity.AuditLogEntity;
import com.ke.infra.mapper.AuditLogMapper;

/**
 * 审计切面（02 §9 / FR-O03）：拦截 {@link Audited} 方法，业务成功返回后向 audit_log 写一行。
 * <ul>
 *   <li>actor = SecurityContext 当前用户（JWT subject 即用户 id）；无认证上下文则留空，不抛错；</li>
 *   <li>object_id = 返回值（Long）或 {@link AuditId} 标记的参数；都取不到则跳过并告警；</li>
 *   <li>detail_json = 方法参数名→toString 快照（每值截 500 字符）；</li>
 *   <li>审计与业务同事务执行：业务成功提交则审计必然落库（集成测试断言依赖此语义）；反之间一 PG 事务中
 *       审计 INSERT 故障（约束/连接等）会 abort 整个事务，业务提交将一并失败——审计与业务完全解耦
 *       （REQUIRES_NEW）记入二期 backlog；</li>
 *   <li>审计写入整体 try/catch：仅隔离审计侧的序列化/解析类失败（如 detail_json 组装），避免这类
 *       可降级故障无谓拖垮业务，并非使数据库级故障与业务解耦。</li>
 * </ul>
 */
@Aspect
@Component
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);
    private static final int DETAIL_VALUE_MAX = 500;

    private final AuditLogMapper auditLogs;
    private final ObjectMapper objectMapper;

    public AuditAspect(AuditLogMapper auditLogs, ObjectMapper objectMapper) {
        this.auditLogs = auditLogs;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(audited)")
    public Object around(ProceedingJoinPoint pjp, Audited audited) throws Throwable {
        Object result = pjp.proceed();
        try {
            record(pjp, audited, result);
        } catch (Exception e) {
            log.error("audit_log 写入失败（同事务已失败，业务提交将一并失败）: action={} objectType={}",
                    audited.action(), audited.objectType(), e);
        }
        return result;
    }

    private void record(ProceedingJoinPoint pjp, Audited audited, Object result) {
        Long objectId = resolveObjectId(pjp, result);
        if (objectId == null) {
            log.warn("审计缺少 objectId，跳过写入: action={} objectType={}", audited.action(), audited.objectType());
            return;
        }
        AuditLogEntity row = new AuditLogEntity();
        row.setActorId(currentUserId());
        row.setAction(audited.action());
        row.setObjectType(audited.objectType());
        row.setObjectId(objectId);
        row.setDetailJson(detailJson(pjp));
        auditLogs.insert(row);
    }

    /** 返回值是 Long 直接用；否则取 @AuditId 标记的参数（Number → long） */
    private Long resolveObjectId(ProceedingJoinPoint pjp, Object result) {
        if (result instanceof Long l) {
            return l;
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Annotation[][] paramAnnotations = method.getParameterAnnotations();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < paramAnnotations.length && i < args.length; i++) {
            for (Annotation a : paramAnnotations[i]) {
                if (a instanceof AuditId && args[i] instanceof Number n) {
                    return n.longValue();
                }
            }
        }
        return null;
    }

    /** actor = 当前用户 id（JWT subject，见 JwtAuthFilter / CardAdminController 同款解析）；
     *  匿名（"anonymousUser"）或无法解析为数字时返回 null（audit_log.actor_id 可空） */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            return null;
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 方法参数快照：参数名→String.valueOf（截 500 字符）；序列化失败降级为 null（列可空） */
    private String detailJson(ProceedingJoinPoint pjp) {
        try {
            MethodSignature signature = (MethodSignature) pjp.getSignature();
            String[] names = signature.getParameterNames();
            Object[] args = pjp.getArgs();
            if (names == null) {
                return null;
            }
            Map<String, String> detail = new LinkedHashMap<>();
            for (int i = 0; i < names.length && i < args.length; i++) {
                if (args[i] == null) {
                    continue;
                }
                String value = String.valueOf(args[i]);
                detail.put(names[i], value.length() > DETAIL_VALUE_MAX
                        ? value.substring(0, DETAIL_VALUE_MAX) : value);
            }
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("审计 detail_json 序列化失败", e);
            return null;
        }
    }
}
