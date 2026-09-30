package com.ke.service.llm;

/** 一次 LLM 调用的完整入参：system 提示词、user 输入与目标档位 */
public record ChatCommand(String system, String user, ModelTier tier) {}
