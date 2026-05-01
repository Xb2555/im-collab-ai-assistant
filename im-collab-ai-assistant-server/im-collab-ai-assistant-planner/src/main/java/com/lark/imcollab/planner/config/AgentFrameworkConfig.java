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

    @Bean(name = "unknownIntentReplyAgent")
    public ReactAgent unknownIntentReplyAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("unknown-intent-reply-agent")
                .description("无法安全路由用户消息时生成自然、简短的 IM 回复")
                .systemPrompt("你是飞书 IM 里的协作型任务 Agent。"
                        + "当用户消息无法安全映射到当前支持的任务意图时，"
                        + "你只生成一句自然、简短、贴合上下文的中文回复。"
                        + "不要输出 JSON，不要解释内部路由，不要假装已经执行动作。")
                .model(chatModel)
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
