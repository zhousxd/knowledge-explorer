package com.ke.service.common;

public record ApiResponse<T>(int code, String message, String traceId, T data) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(0, "ok", TraceId.current(), data); }
    public static ApiResponse<Void> error(int code, String message) { return new ApiResponse<>(code, message, TraceId.current(), null); }
}
