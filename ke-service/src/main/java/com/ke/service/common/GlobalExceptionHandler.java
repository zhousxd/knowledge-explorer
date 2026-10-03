package com.ke.service.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> badRequest(BadRequestException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, e.getMessage()));
    }

    /** content_json 违反模板约束（FR-C03~C06 写前校验）→ 400 envelope，消息含字段路径 */
    @ExceptionHandler(com.ke.domain.card.content.InvalidCardContentException.class)
    public ResponseEntity<ApiResponse<Void>> invalidContent(com.ke.domain.card.content.InvalidCardContentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, e.getMessage()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(404, e.getMessage()));
    }

    /** 服务层 401（登录凭证错误/验证码错误/无效刷新令牌等）：消息本身已面向用户，原样透出 */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, e.getMessage()));
    }

    /** 过滤器链/MVC 层的 Spring 认证异常（JWT 缺失或失效）：统一通用文案，不泄露内部细节 */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> authenticationFailure(
            org.springframework.security.core.AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(401, "未认证或凭证无效"));
    }

    /** MVC 层内（如方法安全 @PreAuthorize、服务层归属/自审校验）抛出的越权 → 403 envelope；
     *  异常自带消息（如「不能发布自己提交的内容」）优先透出，否则用通用文案；过滤器链层走 AccessDeniedHandler */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> forbidden(org.springframework.security.access.AccessDeniedException e) {
        String message = e.getMessage() == null || e.getMessage().isBlank() ? "无权执行该操作" : e.getMessage();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(403, message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, "请求体格式错误"));
    }

    /** 频控（如短信发送 60s 冷却）→ 429 envelope */
    @ExceptionHandler(RateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> rateLimited(RateLimitException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(ApiResponse.error(429, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
            .findFirst().map(f -> f.getField() + " " + f.getDefaultMessage()).orElse("参数错误");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, msg));
    }

    /** multipart 缺少指定 part（如资产导入未带 file 字段）→ 400 envelope */
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<ApiResponse<Void>> missingPart(
            org.springframework.web.multipart.support.MissingServletRequestPartException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, "缺少上传文件"));
    }

    /** 上传文件超出 multipart 限额（spring.servlet.multipart.max-file-size）→ 400 envelope，不落 500 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> oversized(
            org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(400, "上传文件过大（上限 5MB）"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error(500, "服务暂时不可用"));
    }
}
