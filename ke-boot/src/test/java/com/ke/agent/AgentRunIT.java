package com.ke.agent;

import com.ke.service.agent.AgentRunService;
import com.ke.support.ItDbReset;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent_run 异步执行骨架往返：submit 落库 QUEUED → 虚拟线程异步执行 → DONE + latency/model。
 * 直连 WSL 的 ke_test（无 Testcontainers）；MockLlmGateway 秒回，Awaitility 5s 是异步路径的上界。
 * 本测试写数据，类开始前由 ItDbReset 清库、Flyway 重建。
 * properties 标记让本类与 AuthFlowIT 持有不同的 Spring 上下文缓存 key：
 * 清库后共享缓存上下文不会重跑 Flyway，后执行的类会找不到表（顺序相关的失败）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ke.test.agent-run-it = true")
@ActiveProfiles("test")
@ExtendWith(ItDbReset.class)
class AgentRunIT {

    @Autowired
    AgentRunService runs;

    @Test
    void submitReachesDoneAndRecordsLatency() {
        Long runId = runs.submit(null, "explain", "为什么岳麓书院建在岳麓山下？");
        Awaitility.await().atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                var run = runs.get(runId);
                assertThat(run.getStatus()).isEqualTo("DONE");
                assertThat(run.getLatencyMs()).isNotNull().isGreaterThanOrEqualTo(0);
                assertThat(run.getModel()).isNotBlank();
            });
    }
}
