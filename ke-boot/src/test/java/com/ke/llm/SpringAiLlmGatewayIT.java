package com.ke.llm;

import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用 OpenAI 兼容的 MockWebServer 验证 SpringAiLlmGateway 的真实 HTTP 行为。
 * 不打真实 LLM API，也不启用 Testcontainers：直连共享测试库 ke_test（仅上下文加载时 Flyway 校验，无数据写入）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("mockllm")
class SpringAiLlmGatewayIT {

    static MockWebServer llm;

    @BeforeAll
    static void startLlm() throws Exception {
        llm = new MockWebServer();
        llm.enqueue(new MockResponse()
            .addHeader("Content-Type", "application/json")
            .setBody("""
                {"choices":[{"message":{"role":"assistant","content":"你好，来自 mock LLM"}}]}
                """));
        llm.start();
    }

    @AfterAll
    static void stopLlm() throws Exception {
        llm.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // Spring AI 默认补全路径为 /v1/chat/completions，base-url 不应再带 /v1（与 DeepSeek base-url 约定一致）
        r.add("spring.ai.openai.base-url", () -> llm.url("/").toString());
        r.add("spring.ai.openai.api-key", () -> "dummy");
    }

    @Autowired
    LlmGateway gateway;

    @Test
    void generatorTierCallsOpenAiCompatibleEndpoint() throws Exception {
        String out = gateway.complete(new ChatCommand("你是讲解员", "解释一句话", ModelTier.GENERATOR));
        assertThat(out).isEqualTo("你好，来自 mock LLM");
        var recorded = llm.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(recorded.getBody().readUtf8()).contains("解释一句话");
    }
}
