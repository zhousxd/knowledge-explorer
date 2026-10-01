package com.ke.service.auth;

/**
 * 短信发送通道抽象：dev/test 走 DevSmsSender（日志 + 测试取码标记），
 * prod 走 HttpSmsSender（短信网关表单 POST）。
 */
public interface SmsSender {
    void send(String phone, String code);
}
