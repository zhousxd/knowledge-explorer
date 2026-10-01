package com.ke.agent;

import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * explain 流水线 IT 专用网关（profile explain-test + @Primary 压过 test profile 的 MockLlmGateway）：
 * 按序弹出静态队列里的应答（跨异步线程可见），队列耗尽后重复最后一次应答；
 * 未配置任何应答则抛异常（触发流水线的失败路径）。CALLS 供「重试恰好一次」断言。
 * profile 无关的替代方案（@MockitoBean）需要每测试类重建上下文，成本更高，故取静态可变字段。
 */
@Component
@Primary
@Profile("explain-test")
public class StubLlmGateway implements LlmGateway {

    public static final AtomicInteger CALLS = new AtomicInteger();
    private static final Queue<String> RESPONSES = new ConcurrentLinkedQueue<>();
    private static final AtomicReference<String> LAST = new AtomicReference<>();
    /** 最近一次 complete 收到的命令（NlIntentIT 断言档位/提示词面/输入截断用） */
    private static final AtomicReference<ChatCommand> LAST_COMMAND = new AtomicReference<>();
    /** 每次 complete 前的模拟耗时（Task 20 超时护栏 IT 用：delay > 超时阈值 → 触发 TIMEOUT） */
    private static final AtomicLong DELAY_MS = new AtomicLong();

    /** 每个测试方法前重置：清计数、按序装入应答、清延迟 */
    public static void reset(String... responses) {
        CALLS.set(0);
        RESPONSES.clear();
        LAST.set(null);
        LAST_COMMAND.set(null);
        DELAY_MS.set(0);
        Collections.addAll(RESPONSES, responses);
    }

    /** 最近一次 complete 收到的命令；本测试内未调用过则为 null */
    public static ChatCommand lastCommand() {
        return LAST_COMMAND.get();
    }

    /** 静态配置模拟耗时（毫秒）；0 = 立即返回 */
    public static void setDelayMs(long ms) {
        DELAY_MS.set(ms);
    }

    /** 合法 ExplainOutput JSON（可指定引用的 assetId） */
    public static String validOutput(long assetId) {
        return "{\"summary\":\"讲解摘要\",\"sections\":[{\"body\":\"依据资料的正文\",\"claimType\":\"FACT\",\"citations\":["
                + assetId + "]}],\"openQuestions\":[\"开放问题一\"],\"evidenceGaps\":[]}";
    }

    /** 合法 ExplainOutput JSON：FACT 段引用给定 assetId 列表（可含越界 id，Task 19 校验场景用） */
    public static String factOutput(long... assetIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < assetIds.length; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append(assetIds[i]);
        }
        return "{\"summary\":\"讲解摘要\",\"sections\":[{\"body\":\"依据资料的正文\",\"claimType\":\"FACT\",\"citations\":["
                + ids + "]}],\"openQuestions\":[],\"evidenceGaps\":[]}";
    }

    @Override
    public String complete(ChatCommand command) {
        LAST_COMMAND.set(command);
        CALLS.incrementAndGet();
        long delay = DELAY_MS.get();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("StubLlmGateway 睡眠被中断", e);
            }
        }
        String next = RESPONSES.poll();
        if (next == null) {
            next = LAST.get();
            if (next == null) {
                throw new IllegalStateException("StubLlmGateway 未配置应答");
            }
        } else {
            LAST.set(next);
        }
        return next;
    }
}
