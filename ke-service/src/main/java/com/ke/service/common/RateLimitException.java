package com.ke.service.common;

/** 触发频控（如短信发送 60s 冷却）→ 429 envelope */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) { super(message); }
}
