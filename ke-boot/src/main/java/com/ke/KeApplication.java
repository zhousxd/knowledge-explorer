package com.ke;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@MapperScan("com.ke.infra.mapper")
public class KeApplication {
    public static void main(String[] args) {
        SpringApplication.run(KeApplication.class, args);
    }
}
