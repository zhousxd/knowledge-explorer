package com.ke.service.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;

import java.util.concurrent.Executors;

/** 02 §5.1：LLM 秒级阻塞 IO，每任务一个虚拟线程，无需大线程池 */
@Configuration
public class AgentExecutorConfig {
    @Bean("agentExecutor")
    public TaskExecutor agentExecutor() {
        return new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor());
    }
}
