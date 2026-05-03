package com.lark.imcollab.planner.intent;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ArtifactIntentResolver {

    private static final List<String> ALLOWED_ARTIFACTS = List.of("DOC", "PPT", "WHITEBOARD");

    private final LlmChoiceResolver llmChoiceResolver;

    public ArtifactIntentResolver(LlmChoiceResolver llmChoiceResolver) {
        this.llmChoiceResolver = llmChoiceResolver;
    }

    public List<String> resolveArtifacts(String instruction) {
        List<String> choices = llmChoiceResolver.chooseMany(
                safe(instruction),
                ALLOWED_ARTIFACTS,
                """
                你负责识别用户明确想要的交付物类型。
                规则：
                1. 只能从给定可选值中选择，允许多选。
                2. 只有用户确实要求对应交付物时才选择，不要因为标题、例子或上下文里偶然出现词汇就选择。
                3. 写文章、总结、方案、技术文档、会议纪要、材料沉淀可选 DOC。
                4. 演示稿、汇报页、幻灯片可选 PPT。
                5. 白板、画板、流程图、架构图、脑图等可视化画布可选 WHITEBOARD。
                """
        );
        List<String> allowed = choices.stream()
                .filter(ALLOWED_ARTIFACTS::contains)
                .distinct()
                .toList();
        return allowed.isEmpty() ? List.of("DOC") : allowed;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
