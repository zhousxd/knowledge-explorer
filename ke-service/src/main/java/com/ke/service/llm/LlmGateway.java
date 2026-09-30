package com.ke.service.llm;

/** 02 §12.4：所有 LLM 调用收口于此接口；集成测试用 Mock，不依赖真实 API */
public interface LlmGateway {
    String complete(ChatCommand command);
}
