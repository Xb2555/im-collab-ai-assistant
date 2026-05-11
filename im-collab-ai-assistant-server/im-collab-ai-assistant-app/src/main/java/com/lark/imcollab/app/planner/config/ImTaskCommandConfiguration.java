package com.lark.imcollab.app.planner.config;

import com.lark.imcollab.app.planner.facade.DefaultImTaskCommandFacade;
import com.lark.imcollab.app.planner.facade.PlannerExecutionReviewService;
import com.lark.imcollab.app.planner.facade.TaskArtifactResetService;
import com.lark.imcollab.common.facade.HarnessFacade;
import com.lark.imcollab.common.facade.ImTaskCommandFacade;
import com.lark.imcollab.common.facade.TaskUserNotificationFacade;
import com.lark.imcollab.common.service.TaskCancellationRegistry;
import com.lark.imcollab.harness.support.HarnessExecutionLockRecoveryService;
import com.lark.imcollab.planner.config.PlannerExecutionProperties;
import com.lark.imcollab.planner.service.PlannerRetryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskBridgeService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

@Configuration
public class ImTaskCommandConfiguration {

    @Bean
    public ImTaskCommandFacade imTaskCommandFacade(
            PlannerSessionService sessionService,
            TaskBridgeService taskBridgeService,
            PlannerRetryService plannerRetryService,
            TaskRuntimeService taskRuntimeService,
            HarnessFacade harnessFacade,
            PlannerExecutionReviewService reviewService,
            TaskArtifactResetService taskArtifactResetService,
            List<TaskUserNotificationFacade> notificationFacades,
            @Qualifier("executionTaskExecutor") AsyncTaskExecutor executionExecutor,
            ScheduledExecutorService executionTimeoutScheduler,
            PlannerExecutionProperties executionProperties,
            TaskCancellationRegistry cancellationRegistry,
            HarnessExecutionLockRecoveryService lockRecoveryService
    ) {
        return new DefaultImTaskCommandFacade(
                sessionService,
                taskBridgeService,
                plannerRetryService,
                taskRuntimeService,
                harnessFacade,
                reviewService,
                taskArtifactResetService,
                notificationFacades,
                executionExecutor,
                executionTimeoutScheduler,
                executionProperties,
                cancellationRegistry,
                lockRecoveryService
        );
    }
}
