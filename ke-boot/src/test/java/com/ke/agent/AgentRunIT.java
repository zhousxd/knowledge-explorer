package com.ke.agent;

import com.ke.service.agent.AgentRunService;
import com.ke.support.ItDb;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * agent_run 异步执行骨架往返：submit 落库 QUEUED → 虚拟线程异步执行 → DONE + latency/model。
 * 直连 WSL 的 ke_test（无 Testcontainers）；MockLlmGateway 秒回，Awaitility 5s 是异步路径的上界。
 * 本测试写数据：@ItDb 在类开始前清库重建，类结束后 @DirtiesContext 关闭上下文，
 * 防止后续类复用 Flyway 状态已被清库作废的缓存上下文（顺序相关的失败）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@ItDb
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
