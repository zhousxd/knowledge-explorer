package com.ke.service.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 每请求写入 traceId 并在请求结束后清理，避免线程复用导致相邻请求共享同一 traceId
 * （此前 ThreadLocal 仅在首次读取时惰性生成，Tomcat 工作线程复用时旧值残留）。
 * 顺序：必须先于安全过滤器链（order -100）执行——安全链的 401/403 envelope 直接在
 * AuthenticationEntryPoint / AccessDeniedHandler 里读 TraceId，若本过滤器在其后，
 * 被拒绝的请求根本走不到这里，清理也就不会发生。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            TraceId.set(UUID.randomUUID().toString().substring(0, 8));
            filterChain.doFilter(request, response);
        } finally {
            TraceId.clear();
        }
    }
}
