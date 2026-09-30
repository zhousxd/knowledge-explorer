package com.ke.service.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Spring AI 双档实现：按 ModelTier 路由到 generator / router 两个 ChatClient（各绑不同模型） */
@Component
@Profile("!test")
public class SpringAiLlmGateway implements LlmGateway {

    private final ChatClient generator;
    private final ChatClient router;

    public SpringAiLlmGateway(ChatModel chatModel,
                              @Value("${ke.llm.generator-model}") String generatorModel,
                              @Value("${ke.llm.router-model}") String routerModel) {
        this.generator = ChatClient.builder(chatModel)
            .defaultOptions(OpenAiChatOptions.builder().model(generatorModel).build()).build();
        this.router = ChatClient.builder(chatModel)
            .defaultOptions(OpenAiChatOptions.builder().model(routerModel).build()).build();
    }

    @Override
    public String complete(ChatCommand command) {
        ChatClient client = command.tier() == ModelTier.GENERATOR ? generator : router;
        return client.prompt().system(command.system()).user(command.user()).call().content();
    }
}
