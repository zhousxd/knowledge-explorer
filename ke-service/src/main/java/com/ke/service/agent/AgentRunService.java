package com.ke.service.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ke.domain.enums.AgentRunStatus;
import com.ke.infra.entity.AgentRunEntity;
import com.ke.infra.mapper.AgentRunMapper;
import com.ke.service.llm.ChatCommand;
import com.ke.service.llm.LlmGateway;
import com.ke.service.llm.ModelTier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

@Service
public class AgentRunService {

    private final AgentRunMapper runs;
    private final LlmGateway llm;
    private final ObjectMapper objectMapper;
    /** 自代理：submit 必须经代理调 execute，同类 this 调用会让 @Async 失效退化为同步 */
    private final AgentRunService self;

    @Value("${ke.llm.generator-model:unknown}")
    private String generatorModel;

    @Value("${ke.agent.stale-seconds:120}")
    private int staleSeconds;

    public AgentRunService(AgentRunMapper runs, LlmGateway llm, ObjectMapper objectMapper,
                           @Lazy AgentRunService self) {
        this.runs = runs;
        this.llm = llm;
        this.objectMapper = objectMapper;
        this.self = self;
    }

    public AgentRunEntity get(Long id) { return runs.selectById(id); }

    /** 落库 QUEUED 并立即返回 runId，执行走虚拟线程异步（W2 轮询端点复用 get） */
    public Long submit(Long sessionId, String serviceType, String userInput) {
        AgentRunEntity run = new AgentRunEntity();
        run.setSessionId(sessionId);
        run.setServiceType(serviceType);
        run.setInputJson(json(Map.of("input", userInput == null ? "" : userInput)));
        run.setStatus(AgentRunStatus.QUEUED.name());
        runs.insert(run);
        self.execute(run.getId(), userInput);
        return run.getId();
    }

    @Async("agentExecutor")
    public void execute(Long runId, String userInput) {
        transition(runId, AgentRunStatus.RUNNING, null);
        long start = System.currentTimeMillis();
        try {
            // 产物解析/落 artifact 表从 W2 开始，本骨架只记录耗时与模型档
            String out = llm.complete(new ChatCommand("你是知识讲解员，只依据给定资料回答。",
                userInput, ModelTier.GENERATOR));
            AgentRunEntity done = runs.selectById(runId);
            // 直接整体更新：迁移来源恒为 RUNNING，RUNNING→DONE 合法（见 transition 注释）
            done.setStatus(AgentRunStatus.DONE.name());
            done.setLatencyMs((int) (System.currentTimeMillis() - start));
            done.setModel(generatorModel);
            done.setArtifactIds(json(List.of()));
            runs.updateById(done);
        } catch (Exception e) {
            AgentRunEntity failed = runs.selectById(runId);
            failed.setStatus(AgentRunStatus.FAILED.name());
            failed.setError(e.getMessage());
            runs.updateById(failed);
        }
    }

    /**
     * 仅守护 QUEUED→RUNNING 这一步。DONE/FAILED 由 execute 直接写字段后整体 updateById：
     * 迁移来源恒为 RUNNING，RUNNING→DONE/FAILED 在状态机里合法，无需再走此守卫。
     * 已知边界：若心跳回收已判 TIMEOUT，此处的直接写会覆盖终态——W4 接 Resilience4j
     * 超时控制时一并收敛。
     */
    private void transition(Long runId, AgentRunStatus next, String error) {
        AgentRunEntity run = runs.selectById(runId);
        AgentRunStatus current = AgentRunStatus.valueOf(run.getStatus());
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException("非法状态迁移 " + current + "->" + next);
        }
        run.setStatus(next.name());
        if (error != null) run.setError(error);
        runs.updateById(run);
    }

    /** 心跳回收：W4 接 Resilience4j 60s 超时后，此兜底防卡死（02 §5.1） */
    @Scheduled(fixedDelayString = "${ke.agent.recycle-interval-ms:60000}")
    public void recycleStale() {
        runs.recycleStale(staleSeconds);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
