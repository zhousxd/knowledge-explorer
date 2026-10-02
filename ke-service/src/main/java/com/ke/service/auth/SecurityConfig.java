package com.ke.service.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.service.common.ApiResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private final JwtAuthFilter jwtFilter;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtFilter, ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain chain(HttpSecurity http) throws Exception {
        http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api/auth/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                 "/actuator/health").permitAll()
                // 探索端匿名浏览（01 文档：浏览无登录要求）：仅公开只读的卡片列表/详情两个 GET；
                // 入口列表（含 viewer 过滤）与全部写路径仍走 anyRequest().authenticated()
                .requestMatchers(HttpMethod.GET, "/api/cards").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/cards/{id:\\d+}").permitAll()
                // 卡片配图与卡片详情同语义（浏览无登录要求）：只读回源磁盘白名单图片，无枚举面
                .requestMatchers(HttpMethod.GET, "/api/images/{id:\\d+}").permitAll()
                // 免登录分享页（FR-H04/H06）：仅 GET /s/{token} 读快照 permitAll；撤销/接续（POST）
                // 不放行——TOKEN 是唯一凭证，泄露面只在「持有 token 可见快照」（02 §9）
                .requestMatchers(HttpMethod.GET, "/s/*").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint())
                                       .accessDeniedHandler(accessDeniedHandler()))
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** 未认证 → 401 envelope（与 GlobalExceptionHandler 的 JSON 约定一致） */
    AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> writeEnvelope(response, HttpStatus.UNAUTHORIZED, "未认证或凭证无效");
    }

    /** 已认证但权限不足 → 403 envelope（过滤器链层拒绝；MVC 内抛出的走 GlobalExceptionHandler） */
    AccessDeniedHandler accessDeniedHandler() {
        return (request, response, accessDeniedException) -> writeEnvelope(response, HttpStatus.FORBIDDEN, "无权执行该操作");
    }

    private void writeEnvelope(jakarta.servlet.http.HttpServletResponse response, HttpStatus status, String message)
            throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(status.value(), message)));
    }
}
