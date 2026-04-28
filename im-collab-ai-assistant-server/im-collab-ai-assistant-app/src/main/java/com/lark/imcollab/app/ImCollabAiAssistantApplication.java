package com.lark.imcollab.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = "com.lark.imcollab")
@ConfigurationPropertiesScan(basePackages = "com.lark.imcollab")
@EnableAsync
public class ImCollabAiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImCollabAiAssistantApplication.class, args);
    }
}
