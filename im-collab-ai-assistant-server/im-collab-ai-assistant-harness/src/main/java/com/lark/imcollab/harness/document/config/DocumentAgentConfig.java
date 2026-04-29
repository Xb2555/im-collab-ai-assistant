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
                        你负责生成正式文档大纲。
                        输出必须是结构化 JSON：
                        - title: 文档标题
                        - templateType: report、meeting_summary、requirements、technical_plan、technical_architecture、technical_introduction、architecture_review 之一
                        - sections: 至少 6 节，优先覆盖背景、目标、方案、风险、分工、时间计划；架构类文档必须覆盖“项目背景、设计目标与非目标、架构原则、模块分层、执行流程/数据流转、风险与边界、演进建议”
                        - 每节包含 heading 和 2-4 个 keyPoints
                        规则：
                        1. 文档总标题只输出纯标题文本，不要带 #。
                        2. 一级正文标题（H2）统一使用中文编号形式，例如：一、项目背景、二、设计目标与非目标、三、架构原则。
                        3. 章节 heading 只输出 H2 标题文本本身，不要带 ##。
                        4. 如果任务是技术架构/技术介绍/方案设计，必须优先选择 technical_architecture 或 technical_plan，不要退化成 report。
                        5. 如果用户要求 Mermaid 图，需要在相关章节 keyPoints 中显式写明图的用途和图类型，例如“输出全局架构分层 Mermaid flowchart TB”“输出数据流转 Mermaid sequenceDiagram”。
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
                        - body: 可直接写入正式文档的 Markdown 正文
                        规则：
                        1. 不要在 body 中重复输出当前章节的 H2 标题，因为外层会自动写成 `## 一、...`。
                        2. body 内的二级标题统一使用 H3，编号格式为 `### 1.1 标题`。
                        3. body 内的三级标题统一使用 H4，编号格式为 `#### 1.1.1 标题`。
                        4. 如果某个章节需要展开列表、原则、约束、接口、风险，请优先用 H3/H4 组织，而不是一整段散文。
                        5. 禁止输出“待补充”“略”“TBD”“TODO”。
                        6. 如果上下文不足，也要基于任务目标先产出专业且自洽的默认内容，不要空缺。
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
                        - 第一行必须是合法图类型声明，例如 flowchart TB、sequenceDiagram、stateDiagram-v2
                        - 不要返回 JSON
                        - 不要返回 heading/body 字段
                        - 不要写解释、前言、后记
                        - 不要使用 Markdown 围栏
                        风格要求：
                        1. 架构流程图优先使用 `flowchart TB`，允许使用 `subgraph`、方框标题、`<br/>` 换行，风格参考分层架构图。
                        2. 数据流转图优先使用 `sequenceDiagram`，显式 participant，允许 `Note over`、`alt`、`opt` 表达分支和补充说明。
                        3. 节点命名必须贴合当前任务领域，不要使用过于抽象的 Node1/Node2。
                        4. 禁止输出不可解析的伪 Mermaid 语法。
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
                        规则：
                        1. 如果发现缺项但可以直接补全，请在 supplementalSections 中补出完整内容，而不是只报缺失。
                        2. 如果 supplementalSections 已足以解决缺项，missingItems 应尽量置空或仅保留无法自动补齐的问题。
                        3. 禁止建议输出“待补充”“TODO”“TBD”。
                        4. 对架构类文档，额外检查是否覆盖“设计目标与非目标”“Mermaid 图与正文一致性”“模块边界是否清晰”。
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
