package com.ke.service.common;

import java.util.UUID;

public final class TraceId {
    private static final ThreadLocal<String> CTX = new ThreadLocal<>();
    public static String current() {
        String v = CTX.get();
        if (v == null) { v = UUID.randomUUID().toString().substring(0, 8); CTX.set(v); }
        return v;
    }
    public static void clear() { CTX.remove(); }
}
