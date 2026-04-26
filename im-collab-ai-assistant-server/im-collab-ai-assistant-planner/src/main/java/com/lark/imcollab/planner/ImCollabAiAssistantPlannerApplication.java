package com.lark.imcollab.planner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.lark.imcollab"})
@EnableConfigurationProperties
public class ImCollabAiAssistantPlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImCollabAiAssistantPlannerApplication.class, args);
    }
}
