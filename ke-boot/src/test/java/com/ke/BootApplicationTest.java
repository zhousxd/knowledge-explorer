package com.ke;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class BootApplicationTest {
    @Autowired
    Environment env;

    @Test
    void contextLoads() {}

    /** 主 application.yml 不再被 test 目录同名文件遮蔽：虚拟线程基座配置对测试可见 */
    @Test
    void virtualThreadsEnabled() {
        assertThat(env.getProperty("spring.threads.virtual.enabled")).isEqualTo("true");
    }
}
