package com.lark.imcollab.planner.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.SubAgentInterceptor;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.prompt.AgentPromptInterceptor;
import com.lark.imcollab.planner.prompt.PlannerPromptFacade;
import com.lark.imcollab.store.checkpoint.CheckpointSaverProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class AgentFrameworkConfig {

    private static final PlanTaskSession DEFAULT_PROMPT_SESSION = PlanTaskSession.builder()
            .taskId("default")
            .planningPhase(PlanningPhaseEnum.INTAKE)
            .build();

    @Bean
    public SummarizationHook summarizationHook(
            ChatModel chatModel,
            PlannerProperties props,
            PlannerPromptFacade promptFacade) {
        return SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(props.getSummarization().getMaxTokensBeforeSummary())
                .messagesToKeep(props.getSummarization().getMessagesToKeep())
                .summaryPrompt(promptFacade.summarizationPrompt())
                .build();
    }

    @Bean(name = "clarificationAgent")
    public ReactAgent clarificationAgent(
            ChatModel chatModel,
            SummarizationHook summarizationHook,
            PlannerPromptFacade promptFacade,
            AgentPromptInterceptor agentPromptInterceptor) {
        return ReactAgent.builder()
                .name("clarification-agent")
                .description("澄清信息不足时生成反问问题")
                .systemPrompt(promptFacade.clarificationPrompt(DEFAULT_PROMPT_SESSION))
                .model(chatModel)
                .interceptors(agentPromptInterceptor)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "intentAgent")
    public ReactAgent intentAgent(
            ChatModel chatModel,
            SummarizationHook summarizationHook,
            PlannerPromptFacade promptFacade,
            AgentPromptInterceptor agentPromptInterceptor) {
        return ReactAgent.builder()
                .name("intent-agent")
                .description("Extracts a structured intent snapshot before planning")
                .systemPrompt(promptFacade.intentPrompt(DEFAULT_PROMPT_SESSION))
                .outputType(IntentSnapshot.class)
                .model(chatModel)
                .interceptors(agentPromptInterceptor)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "planningAgent")
    public ReactAgent planningAgent(
            ChatModel chatModel,
            SummarizationHook summarizationHook,
            PlannerPromptFacade promptFacade,
            AgentPromptInterceptor agentPromptInterceptor) {
        return ReactAgent.builder()
                .name("planning-agent")
                .description("将用户需求拆解为结构化任务计划")
                .systemPrompt(promptFacade.planningPrompt(DEFAULT_PROMPT_SESSION))
                .outputType(PlanBlueprint.class)
                .model(chatModel)
                .interceptors(agentPromptInterceptor)
                .hooks(summarizationHook)
                .build();
    }

    /**
     * Gives supervisorAgent the ability to call clarificationAgent as a tool.
     */
    @Bean
    public SubAgentInterceptor subAgentInterceptor(
            ChatModel chatModel,
            @Qualifier("clarificationAgent") ReactAgent clarificationAgent) {
        return SubAgentInterceptor.builder()
                .defaultModel(chatModel)
                .includeGeneralPurpose(false)
                .addSubAgent("clarification-agent", clarificationAgent)
                .build();
    }

    @Bean(name = "supervisorAgent")
    public ReactAgent supervisorAgent(
            ChatModel chatModel,
            SubAgentInterceptor subAgentInterceptor,
            SummarizationHook summarizationHook,
            PlannerPromptFacade promptFacade,
            AgentPromptInterceptor agentPromptInterceptor,
            CheckpointSaverProvider checkpointSaverProvider) {
        return ReactAgent.builder()
                .name("supervisor-agent")
                .description("任务规划主控 Agent")
                .systemPrompt(promptFacade.supervisorPrompt(DEFAULT_PROMPT_SESSION))
                .model(chatModel)
                .interceptors(subAgentInterceptor, agentPromptInterceptor)
                .hooks(summarizationHook)
                .saver(checkpointSaverProvider.getCheckpointSaver())
                .build();
    }
}
