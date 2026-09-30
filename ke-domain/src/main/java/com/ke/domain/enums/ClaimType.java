package com.ke.domain.enums;

/** 三档可信（FR-S05）：符号与颜色双编码，symbol 供前端渲染（04 §2.3） */
public enum ClaimType {
    FACT("●"), SYNTHESIS("◐"), GEN("○");

    private final String symbol;
    ClaimType(String symbol) { this.symbol = symbol; }
    public String symbol() { return symbol; }
}
