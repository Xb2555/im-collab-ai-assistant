package com.lark.imcollab.planner;

import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"com.lark.imcollab"})
@EnableConfigurationProperties(LarkCliProperties.class)
public class ImCollabAiAssistantPlannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImCollabAiAssistantPlannerApplication.class, args);
    }
}
