package com.lark.imcollab.planner.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.AgentTool;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.prompt.AgentPromptInterceptor;
import com.lark.imcollab.planner.prompt.PlannerPromptFacade;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.supervisor.PlannerContextTool;
import com.lark.imcollab.planner.supervisor.PlannerExecutionTool;
import com.lark.imcollab.planner.supervisor.PlannerGateTool;
import com.lark.imcollab.planner.supervisor.PlannerMemoryTool;
import com.lark.imcollab.planner.supervisor.PlannerPatchTool;
import com.lark.imcollab.planner.supervisor.PlannerQuestionTool;
import com.lark.imcollab.planner.supervisor.PlannerReviewTool;
import com.lark.imcollab.planner.supervisor.PlannerRuntimeTool;
import com.lark.imcollab.planner.supervisor.ContextSufficiencyResult;
import com.lark.imcollab.planner.supervisor.PlanReviewResult;
import com.lark.imcollab.store.checkpoint.CheckpointSaverProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

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

    @Bean(name = "intentClassifierAgent")
    public ReactAgent intentClassifierAgent(ChatModel chatModel, SummarizationHook summarizationHook) {
        return ReactAgent.builder()
                .name("intent-classifier-agent")
                .description("Scenario B: choose one fixed user command intent for the current planner conversation")
                .systemPrompt("""
                        You are a narrow intent classifier for the Planner StateGraph.
                        Return JSON only. Never generate plan steps, never execute actions, never answer the user.
                        Allowed intents: START_TASK, ANSWER_CLARIFICATION, ADJUST_PLAN, QUERY_STATUS, CONFIRM_ACTION, CANCEL_TASK, UNKNOWN.
                        Choose ADJUST_PLAN when the user wants any new, extra, final, changed, removed, reordered, or regenerated plan requirement.
                        Choose QUERY_STATUS only when the user asks to inspect progress, current status, existing artifacts, or the already stored plan.
                        Choose CONFIRM_ACTION only when the user explicitly asks to execute or retry, such as "开始执行", "确认执行", "没问题，执行", or "重试一下".
                        Generic approval such as "这个方案还行", "可以", or "就这样" is not enough to start execution.
                        If the user asks to add something and also mentions a desired output, it is ADJUST_PLAN, not QUERY_STATUS.
                        Choose UNKNOWN only when the message cannot be safely mapped to one fixed intent.
                        JSON shape: {"intent":"...","confidence":0.0,"reason":"","normalizedInput":"","needsClarification":false,"readOnlyView":"PLAN|STATUS|ARTIFACTS|"}
                        For QUERY_STATUS, set readOnlyView=PLAN when the user asks to see the stored plan or all steps.
                        Set readOnlyView=STATUS when the user asks progress/current state.
                        Set readOnlyView=ARTIFACTS when the user asks outputs, links, or generated artifacts.
                        """)
                .model(chatModel)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "unknownIntentReplyAgent")
    public ReactAgent unknownIntentReplyAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("unknown-intent-reply-agent")
                .description("无法安全路由用户消息时生成自然、简短的 IM 回复")
                .systemPrompt("""
                        你是飞书 IM 里的协作型任务 Agent，像同事一样简短回应。
                        当用户消息无法安全映射到当前支持的任务意图时，你只生成一句自然中文回复。
                        回复必须贴合上下文，尽量带上用户原话或当前计划里的一个具体点，避免固定模板。
                        如果用户只是认可、评价或轻微反馈，就确认会保留当前计划，不要说“没理解”。
                        如果用户像是在要查看计划或进度，就自然告诉他可以继续查看，但不要声称计划已改变。
                        不要输出 JSON，不要解释内部路由，不要假装已经执行、生成或完成任何动作。
                        不要使用“我没完全判断清楚”这类机械话术。
                        """)
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

    @Bean(name = "replanAgent")
    public ReactAgent replanAgent(ChatModel chatModel, SummarizationHook summarizationHook) {
        return ReactAgent.builder()
                .name("replan-agent")
                .description("Scenario B: interpret one natural language plan adjustment as a local patch intent")
                .systemPrompt("""
                        You are the Planner replan sub-agent inside a Spring AI Alibaba StateGraph.
                        Your only job is to convert one user adjustment into a local PlanPatchIntent JSON.
                        Do not generate a full plan. Do not execute. Do not answer the user.
                        Preserve all untouched existing steps by default.
                        Allowed operations: ADD_STEP, REMOVE_STEP, UPDATE_STEP, MERGE_STEP, REORDER_STEP, REGENERATE_ALL, CLARIFY_REQUIRED.
                        Use ADD_STEP whenever the user asks for an extra, additional, final, also-needed, or one-more deliverable.
                        ADD_STEP must include one or more newCardDrafts and must not target existing cards.
                        REMOVE_STEP and UPDATE_STEP must target existing card ids only.
                        Use UPDATE_STEP when the user says to change one existing step into another form, remove wording from an existing step, or says "把/将 X 改成 Y" / "不要提 Z"; include the rewritten newCardDraft.
                        Use MERGE_STEP when the user says an existing step should no longer be separate and should be folded into another step. targetCardIds must put the destination card first, then the source card(s), and newCardDrafts must contain the rewritten destination card.
                        If the user describes a unique current card semantically, target that card id instead of asking a clarification.
                        If the user says keep A and do not do B, remove only B and preserve all other steps.
                        REORDER_STEP must return every current card id exactly once.
                        REGENERATE_ALL only when the user explicitly asks to redo the whole plan.
                        Stable deliverables are DOC, PPT, SUMMARY. Mermaid is a DOC content requirement.
                        Return JSON only with fields:
                        {"operation":"","targetCardIds":[],"orderedCardIds":[],"newCardDrafts":[{"title":"","description":"","type":"DOC|PPT|SUMMARY"}],"confidence":0.0,"reason":"","clarificationQuestion":""}
                        """)
                .outputType(PlanPatchIntent.class)
                .model(chatModel)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "contextCollectorAgent")
    public ReactAgent contextCollectorAgent(ChatModel chatModel, SummarizationHook summarizationHook) {
        return ReactAgent.builder()
                .name("context-collector-agent")
                .description("判断任务上下文是否足够并生成上下文摘要")
                .systemPrompt("""
                        你是 Planner 的上下文收集子 Agent。
                        你只判断当前用户输入、workspace context 和对话记忆是否足够支撑规划。
                        如果 workspace context 只有用户最新指令本身，没有选中消息、文档引用、附件或具体主题材料，不能把这句话当作“可整理内容”。
                        对“整理一下 / 给老板看 / 做个汇报 / 总结一下”这类依赖材料的请求，如果没有真实材料，必须返回上下文不足，并自然追问需要基于哪些内容以及输出形式。
                        如果用户请求当前不稳定支持的白板、画布、直接发 IM、归档、真实搜索等产物，不要把它当成可执行步骤；请自然询问是否转成文档中的 Mermaid 图、PPT 页面或摘要。
                        如果不足，只返回缺失信息和一个自然反问。
                        不要生成计划，不要执行任务，不要声称已经收集到外部资料。
                当前 Planner 稳定支持 DOC、PPT、SUMMARY；Mermaid 只能作为 DOC 内容要求。
                        """)
                .outputType(ContextSufficiencyResult.class)
                .model(chatModel)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "planReviewAgent")
    public ReactAgent planReviewAgent(ChatModel chatModel, SummarizationHook summarizationHook) {
        return ReactAgent.builder()
                .name("plan-review-agent")
                .description("审查 Planner 计划是否保留原目标、未误删步骤且未越界")
                .systemPrompt("""
                        你是 Planner 的计划审查子 Agent。
                        你只审查计划，不执行任务。
                        重点检查：是否丢失用户原始目标，是否误删已有步骤，是否新增未知产物或未知 worker。
                        当前只允许 DOC、PPT、SUMMARY。Mermaid 是 DOC 内容要求，不是独立产物。
                        输出应简短、结构化、可被代码 gate 进一步校验。
                        """)
                .outputType(PlanReviewResult.class)
                .model(chatModel)
                .hooks(summarizationHook)
                .build();
    }

    @Bean(name = "runtimeStatusAgent")
    public ReactAgent runtimeStatusAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("runtime-status-agent")
                .description("把任务 runtime snapshot 转为自然简短的用户回复")
                .systemPrompt("""
                        你是飞书 IM 和 GUI 中的任务状态播报 Agent。
                        你只能根据 runtime snapshot 描述当前进度和产物链接。
                        不要触发 replan，不要假装执行任务，不要输出内部异常或 JSON。
                        回复要自然、简短、像协作者。
                        """)
                .model(chatModel)
                .build();
    }

    @Bean(name = "supervisorAgent")
    public ReactAgent supervisorAgent(
            ChatModel chatModel,
            @Qualifier("clarificationAgent") ReactAgent clarificationAgent,
            @Qualifier("intentAgent") ReactAgent intentAgent,
            @Qualifier("intentClassifierAgent") ReactAgent intentClassifierAgent,
            @Qualifier("planningAgent") ReactAgent planningAgent,
            @Qualifier("replanAgent") ReactAgent replanAgent,
            @Qualifier("contextCollectorAgent") ReactAgent contextCollectorAgent,
            @Qualifier("planReviewAgent") ReactAgent planReviewAgent,
            @Qualifier("runtimeStatusAgent") ReactAgent runtimeStatusAgent,
            PlannerMemoryTool plannerMemoryTool,
            PlannerRuntimeTool plannerRuntimeTool,
            PlannerGateTool plannerGateTool,
            PlannerPatchTool plannerPatchTool,
            PlannerContextTool plannerContextTool,
            PlannerQuestionTool plannerQuestionTool,
            PlannerExecutionTool plannerExecutionTool,
            PlannerReviewTool plannerReviewTool,
            SummarizationHook summarizationHook,
            PlannerPromptFacade promptFacade,
            AgentPromptInterceptor agentPromptInterceptor,
            CheckpointSaverProvider checkpointSaverProvider) {
        List<ToolCallback> plannerSubAgentTools = List.of(
                AgentTool.create(clarificationAgent),
                AgentTool.create(intentAgent),
                AgentTool.create(intentClassifierAgent),
                AgentTool.create(planningAgent),
                AgentTool.create(replanAgent),
                AgentTool.create(contextCollectorAgent),
                AgentTool.create(planReviewAgent),
                AgentTool.create(runtimeStatusAgent)
        );
        return ReactAgent.builder()
                .name("supervisor-agent")
                .description("任务规划主控 Agent")
                .systemPrompt(promptFacade.supervisorPrompt(DEFAULT_PROMPT_SESSION))
                .model(chatModel)
                .tools(plannerSubAgentTools)
                .methodTools(
                        plannerMemoryTool,
                        plannerRuntimeTool,
                        plannerGateTool,
                        plannerPatchTool,
                        plannerContextTool,
                        plannerQuestionTool,
                        plannerExecutionTool,
                        plannerReviewTool
                )
                .interceptors(agentPromptInterceptor)
                .hooks(summarizationHook)
                .saver(checkpointSaverProvider.getCheckpointSaver())
                .build();
    }
}
