package com.ke.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI keOpenApi() {
        return new OpenAPI().info(new Info()
            .title("Knowledge Explorer API")
            .description("知识探索卡片系统——错误 envelope: {code, message, traceId, data}")
            .version("0.1.0"));
    }
}
