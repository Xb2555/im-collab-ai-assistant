package com.lark.imcollab.harness.scene.c.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SequentialAgent;
import com.lark.imcollab.harness.scene.c.model.SceneCDocOutline;
import com.lark.imcollab.harness.scene.c.model.SceneCDocReviewResult;
import com.lark.imcollab.harness.scene.c.model.SceneCDocSectionDraft;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class SceneCAgentConfig {

    @Bean(name = "sceneCOutlineAgent")
    public ReactAgent sceneCOutlineAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("scene-c-outline-agent")
                .description("为文档任务生成结构化大纲")
                .systemPrompt("""
                        你负责生成场景 C 文档大纲。
                        输出必须是结构化 JSON：
                        - title: 文档标题
                        - templateType: 报告/report、会议总结/meeting_summary、需求说明/requirements、技术方案/technical_plan 之一
                        - sections: 至少 6 节，覆盖背景、目标、方案、风险、分工、时间计划
                        - 每节包含 heading 和 2-4 个 keyPoints
                        """)
                .outputType(SceneCDocOutline.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "sceneCSectionAgent")
    public ReactAgent sceneCSectionAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("scene-c-section-agent")
                .description("按单章节生成正文")
                .systemPrompt("""
                        你负责一次只生成一个章节正文。
                        输出结构化 JSON：
                        - heading: 章节标题
                        - body: 3-6 段正文，信息充分，可直接写入正式文档
                        不要返回 markdown 代码块。
                        """)
                .outputType(SceneCDocSectionDraft.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "sceneCReviewAgent")
    public ReactAgent sceneCReviewAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("scene-c-review-agent")
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
                .outputType(SceneCDocReviewResult.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "sceneCGenerationSequence")
    public SequentialAgent sceneCGenerationSequence(
            @Qualifier("sceneCOutlineAgent") ReactAgent outlineAgent,
            @Qualifier("sceneCReviewAgent") ReactAgent reviewAgent) {
        return SequentialAgent.builder()
                .name("scene-c-generation-sequence")
                .description("场景 C 结构化文档链路")
                .subAgents(List.of(outlineAgent, reviewAgent))
                .build();
    }
}
