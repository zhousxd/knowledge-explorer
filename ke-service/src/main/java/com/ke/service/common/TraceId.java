package com.ke.service.common;

import java.util.UUID;

public final class TraceId {
    private static final ThreadLocal<String> CTX = new ThreadLocal<>();
    public static String current() {
        String v = CTX.get();
        if (v == null) { v = UUID.randomUUID().toString().substring(0, 8); CTX.set(v); }
        return v;
    }
    /** 由 TraceIdFilter 在每个请求入口写入；异步线程无值时仍可在 current() 兜底生成 */
    public static void set(String value) { CTX.set(value); }
    public static void clear() { CTX.remove(); }
}
