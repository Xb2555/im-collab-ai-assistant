package com.lark.imcollab.planner.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerAsyncConfigurationTest {

    @Test
    void plannerAndExecutionExecutorsAreSeparated() {
        PlannerAsyncConfiguration configuration = new PlannerAsyncConfiguration();
        PlannerAsyncProperties asyncProperties = new PlannerAsyncProperties();
        asyncProperties.setCorePoolSize(1);
        asyncProperties.setMaxPoolSize(2);
        asyncProperties.setQueueCapacity(3);

        PlannerExecutionProperties executionProperties = new PlannerExecutionProperties();
        executionProperties.setCorePoolSize(4);
        executionProperties.setMaxPoolSize(5);
        executionProperties.setQueueCapacity(6);

        ThreadPoolTaskExecutor plannerExecutor = configuration.plannerTaskExecutor(asyncProperties);
        ThreadPoolTaskExecutor executionExecutor = configuration.executionTaskExecutor(executionProperties);
        ScheduledExecutorService scheduler = configuration.executionTimeoutScheduler();

        try {
            assertThat(plannerExecutor).isNotSameAs(executionExecutor);
            assertThat(plannerExecutor.getThreadNamePrefix()).isEqualTo("planner-async-");
            assertThat(executionExecutor.getThreadNamePrefix()).isEqualTo("execution-async-");
            assertThat(plannerExecutor.getCorePoolSize()).isEqualTo(1);
            assertThat(executionExecutor.getCorePoolSize()).isEqualTo(4);
        } finally {
            plannerExecutor.shutdown();
            executionExecutor.shutdown();
            scheduler.shutdownNow();
        }
    }
}
