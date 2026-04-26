package com.lark.imcollab.planner.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.extension.interceptor.SubAgentInterceptor;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentFrameworkConfig {

    @Bean
    public SummarizationHook summarizationHook(ChatModel chatModel, PlannerProperties props) {
        return SummarizationHook.builder()
                .model(chatModel)
                .maxTokensBeforeSummary(props.getSummarization().getMaxTokensBeforeSummary())
                .messagesToKeep(props.getSummarization().getMessagesToKeep())
                .summaryPrompt(PlannerPrompts.SUMMARIZATION_PROMPT)
                .build();
    }

    @Bean(name = "clarificationAgent")
    public ReactAgent clarificationAgent(ChatModel chatModel, SummarizationHook summarizationHook) {
        return ReactAgent.builder()
                .name("clarification-agent")
                .description("澄清信息不足时生成反问问题")
                .systemPrompt(PlannerPrompts.CLARIFICATION_SYSTEM)
                .model(chatModel)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "planningAgent")
    public ReactAgent planningAgent(ChatModel chatModel, SummarizationHook summarizationHook) {
        return ReactAgent.builder()
                .name("planning-agent")
                .description("将用户需求拆解为结构化任务计划")
                .systemPrompt(PlannerPrompts.PLANNING_SYSTEM)
                .model(chatModel)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "criticAgent")
    public ReactAgent criticAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("critic-agent")
                .description("对任务计划进行多维度质量评分")
                .systemPrompt(PlannerPrompts.CRITIC_SYSTEM)
                .model(chatModel)
                .build();
    }

    /**
     * SequentialAgent: planner -> critic.
     * Called directly by SupervisorPlannerService for the quality scoring chain.
     * Not wired into SubAgentInterceptor because that only accepts ReactAgent.
     */
    @Bean(name = "planningSequence")
    public SequentialAgent planningSequence(
            @Qualifier("planningAgent") ReactAgent planningAgent,
            @Qualifier("criticAgent") ReactAgent criticAgent,
            @Qualifier("memorySaver") BaseCheckpointSaver checkpointSaver) {
        return SequentialAgent.builder()
                .name("planning-sequence")
                .description("顺序执行：规划 -> 评分")
                .subAgents(List.of(planningAgent, criticAgent))
                .saver(checkpointSaver)
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
            @Qualifier("memorySaver") BaseCheckpointSaver checkpointSaver) {
        return ReactAgent.builder()
                .name("supervisor-agent")
                .description("任务规划主控 Agent")
                .systemPrompt(PlannerPrompts.SUPERVISOR_SYSTEM)
                .model(chatModel)
                .interceptors(subAgentInterceptor)
                .hooks(summarizationHook)
                .saver(checkpointSaver)
                .build();
    }

    @Bean
    public BaseCheckpointSaver checkpointSaver(RedissonClient redissonClient) {
        return RedisSaver.builder()
                .redisson(redissonClient)
                .build();
    }

    @Bean
    public MemorySaver memorySaver() {
        return new MemorySaver();
    }
}
