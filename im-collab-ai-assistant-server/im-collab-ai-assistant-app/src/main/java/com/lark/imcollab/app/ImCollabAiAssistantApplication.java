package com.lark.imcollab.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.lark.imcollab")
@ConfigurationPropertiesScan(basePackages = "com.lark.imcollab")
public class ImCollabAiAssistantApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImCollabAiAssistantApplication.class, args);
    }
}
