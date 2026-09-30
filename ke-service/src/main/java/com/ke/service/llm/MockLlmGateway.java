package com.ke.service.llm;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** test profile 专用：返回固定 JSON，业务/集成测试不依赖真实 LLM API */
@Component
@Profile("test")
public class MockLlmGateway implements LlmGateway {
    @Override
    public String complete(ChatCommand command) {
        return "{\"summary\":\"mock\",\"tier\":\"" + command.tier() + "\"}";
    }
}
