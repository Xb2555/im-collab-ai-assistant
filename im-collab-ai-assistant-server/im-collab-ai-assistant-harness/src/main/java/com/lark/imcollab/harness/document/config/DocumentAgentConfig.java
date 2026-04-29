package com.lark.imcollab.harness.document.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class DocumentAgentConfig {

    @Bean(name = "documentOutlineAgent")
    public ReactAgent documentOutlineAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("document-outline-agent")
                .description("为文档任务生成结构化大纲")
                .systemPrompt("""
                        你负责生成文档大纲。
                        输出必须是结构化 JSON：
                        - title: 文档标题
                        - templateType: 报告/report、会议总结/meeting_summary、需求说明/requirements、技术方案/technical_plan 之一
                        - sections: 至少 6 节，覆盖背景、目标、方案、风险、分工、时间计划
                        - 每节包含 heading 和 2-4 个 keyPoints
                        """)
                .outputType(DocumentOutline.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "documentSectionAgent")
    public ReactAgent documentSectionAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("document-section-agent")
                .description("按单章节生成正文")
                .systemPrompt("""
                        你负责一次只生成一个章节正文。
                        输出结构化 JSON：
                        - heading: 章节标题
                        - body: 3-6 段正文，信息充分，可直接写入正式文档
                        不要返回 markdown 代码块。
                        """)
                .outputType(DocumentSectionDraft.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "documentDiagramAgent")
    public ReactAgent documentDiagramAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("document-diagram-agent")
                .description("按要求生成 Mermaid 图源码")
                .systemPrompt("""
                        你负责生成 Mermaid 图源码。
                        输出要求：
                        - 只返回 Mermaid 源码本体
                        - 第一行必须是合法图类型声明，例如 flowchart TD、sequenceDiagram、stateDiagram-v2
                        - 不要返回 JSON
                        - 不要返回 heading/body 字段
                        - 不要写解释、前言、后记
                        - 不要使用 Markdown 围栏
                        """)
                .model(chatModel)
                .build();
    }

    @Bean(name = "documentReviewAgent")
    public ReactAgent documentReviewAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("document-review-agent")
                .description("审查文档覆盖度并补齐缺项")
                .systemPrompt("""
                        你负责审查文档质量。
                        输出结构化 JSON：
                        - backgroundCovered/goalCovered/solutionCovered/risksCovered/ownershipCovered/timelineCovered
                        - missingItems: 缺失项名称列表
                        - supplementalSections: 若有缺项，给出可直接合并的章节内容列表
                        - summary: 评审结论
                        如果信息已充分，supplementalSections 返回空数组。
                        """)
                .outputType(DocumentReviewResult.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "documentGenerationSequence")
    public SequentialAgent documentGenerationSequence(
            @Qualifier("documentOutlineAgent") ReactAgent outlineAgent,
            @Qualifier("documentReviewAgent") ReactAgent reviewAgent) {
        return SequentialAgent.builder()
                .name("document-generation-sequence")
                .description("结构化文档生成链路")
                .subAgents(List.of(outlineAgent, reviewAgent))
                .build();
    }
}
