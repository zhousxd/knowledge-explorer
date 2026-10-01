package com.ke.entry;

import com.ke.agent.StubLlmGateway;
import com.ke.domain.entry.NlIntent;
import com.ke.service.entry.NlIntentClassifier;
import com.ke.service.llm.ModelTier;
import com.ke.support.ItDb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 入口意图分类端到端（FR-N01 / 02 §5.3，{@link NlIntentClassifier} + 网关调用面）：
 * 网关用 StubLlmGateway（profile explain-test，@Primary 压过 test 的 MockLlmGateway），
 * 每用例前静态设返回值，模式与 ExplainPipelineIT 一致；真实 LLM 语义不做（mock 面）。
 * 钉住：四类白名单各 1 例；越权示例「帮我订机票」→ OUT_OF_SCOPE；非 JSON / 非法枚举
 * 解析失败兜底 OUT_OF_SCOPE（安全侧错报优于漏报）；调用恒走 ROUTER 档、system 提示词
 * 含四类白名单名；空白输入不触发网关；超长输入 500 字防御截断（HTTP 400 在 Task 27 上层管）。
 */
@SpringBootTest
@ActiveProfiles({"test", "explain-test"})
@ItDb
class NlIntentIT {

    @Autowired
    NlIntentClassifier classifier;

    @BeforeEach
    void resetStub() {
        StubLlmGateway.reset("OUT_OF_SCOPE");
    }

    @Test
    void fourWhitelistIntentsRoundTrip() {
        StubLlmGateway.reset("LINK_CARD");
        assertThat(classifier.classify("把这张卡片连成入口")).isEqualTo(NlIntent.LINK_CARD);

        StubLlmGateway.reset("EXPLAIN");
        assertThat(classifier.classify("深入讲讲岳麓书院的讲会制度")).isEqualTo(NlIntent.EXPLAIN);

        StubLlmGateway.reset("COMPARE");
        assertThat(classifier.classify("对比岳麓书院和白鹿洞书院")).isEqualTo(NlIntent.COMPARE);

        StubLlmGateway.reset("OUT_OF_SCOPE");
        assertThat(classifier.classify("今天天气怎么样")).isEqualTo(NlIntent.OUT_OF_SCOPE);
    }

    @Test
    void routerTierAndPromptFace() {
        StubLlmGateway.reset("EXPLAIN");
        classifier.classify("讲讲书院");

        // 路由档小模型（02 §6 模型分级路由）+ 提示词面含四类白名单名
        assertThat(StubLlmGateway.lastCommand().tier()).isEqualTo(ModelTier.ROUTER);
        assertThat(StubLlmGateway.lastCommand().system())
                .contains("LINK_CARD").contains("EXPLAIN")
                .contains("COMPARE").contains("OUT_OF_SCOPE");
        // user 即用户原文（本例未超长，不截断）
        assertThat(StubLlmGateway.lastCommand().user()).isEqualTo("讲讲书院");
    }

    @Test
    void bookingRequestFallsToOutOfScope() {
        StubLlmGateway.reset("OUT_OF_SCOPE");
        assertThat(classifier.classify("帮我订机票")).isEqualTo(NlIntent.OUT_OF_SCOPE);
    }

    @Test
    void nonWhitelistAnswerFallsBackToOutOfScope() {
        // 非 JSON/自然语言应答（如「我不确定」）→ 兜底 OUT_OF_SCOPE
        StubLlmGateway.reset("我不确定该怎么分类");
        assertThat(classifier.classify("讲讲书院")).isEqualTo(NlIntent.OUT_OF_SCOPE);

        // 非法枚举 → 同样兜底
        StubLlmGateway.reset("FOO");
        assertThat(classifier.classify("讲讲书院")).isEqualTo(NlIntent.OUT_OF_SCOPE);
    }

    @Test
    void blankInputShortCircuitsWithoutGateway() {
        StubLlmGateway.reset("EXPLAIN");
        assertThat(classifier.classify("   ")).isEqualTo(NlIntent.OUT_OF_SCOPE);
        assertThat(classifier.classify(null)).isEqualTo(NlIntent.OUT_OF_SCOPE);
        // 空白输入直接安全侧结论，不打网关（省一次近零成本调用，结果等价）
        assertThat(StubLlmGateway.CALLS.get()).isZero();
    }

    @Test
    void overlongInputDefensivelyTruncatedTo500() {
        StubLlmGateway.reset("EXPLAIN");
        classifier.classify("讲".repeat(600));
        assertThat(StubLlmGateway.lastCommand().user()).hasSize(500);
    }
}
