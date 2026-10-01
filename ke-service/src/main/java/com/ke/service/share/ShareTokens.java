package com.ke.service.share;

import java.security.SecureRandom;

/**
 * 分享 token 生成（FR-H06 安全线）：自实现 nanoid 风格 Base62，{@link SecureRandom} 不可枚举，
 * 不引第三方库。21 位 Base62 ≈ 125 bit 熵（62^21 ≈ 5.4×10^37），遍历/撞库不可行；
 * share.token 列 UNIQUE 兜底，冲突由 ShareService 换号重试（上限 3 次，实际概率可忽略）。
 */
public final class ShareTokens {

    /** token 长度（share.token VARCHAR(21) 契约） */
    public static final int LENGTH = 21;

    private static final String ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShareTokens() {
    }

    /** 生成一个 21 位 Base62 token（每次调用独立随机，不保证全局唯一——UNIQUE 约束兜底） */
    public static String next() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
