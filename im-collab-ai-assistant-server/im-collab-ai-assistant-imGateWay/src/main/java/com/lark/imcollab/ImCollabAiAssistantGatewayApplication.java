package com.lark.imcollab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackages = "com.lark.imcollab.skills")
public class ImCollabAiAssistantGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImCollabAiAssistantGatewayApplication.class, args);
    }
}
