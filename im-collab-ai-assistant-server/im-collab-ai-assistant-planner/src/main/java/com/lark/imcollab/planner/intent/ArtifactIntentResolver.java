package com.lark.imcollab.planner.intent;

import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class ArtifactIntentResolver {

    private static final List<String> ALLOWED_ARTIFACTS = List.of("DOC", "PPT", "WHITEBOARD");

    private final LlmChoiceResolver llmChoiceResolver;

    public ArtifactIntentResolver(LlmChoiceResolver llmChoiceResolver) {
        this.llmChoiceResolver = llmChoiceResolver;
    }

    public List<String> resolveArtifacts(String instruction) {
        String text = safe(instruction).toLowerCase(Locale.ROOT);
        Set<String> artifacts = new LinkedHashSet<>();

        if (containsAny(text, "文档", "doc", "报告", "方案", "纪要", "prd")) {
            artifacts.add("DOC");
        }
        if (containsAny(text, "ppt", "slide", "slides", "演示", "幻灯片", "汇报材料")) {
            artifacts.add("PPT");
        }
        if (containsAny(text, "白板", "whiteboard", "流程图", "架构图", "脑图", "mermaid")) {
            artifacts.add("WHITEBOARD");
        }
        if (!artifacts.isEmpty()) {
            return List.copyOf(artifacts);
        }

        String choice = llmChoiceResolver.chooseOne(
                instruction,
                ALLOWED_ARTIFACTS,
                """
                你负责识别用户想要的主要交付物类型。
                规则：
                1. 只能从给定可选值中选择一个。
                2. 如果用户更像是要写文章、总结、方案、技术文档，选 DOC。
                3. 如果用户更像是要做演示稿、汇报页、幻灯片，选 PPT。
                4. 如果用户更像是要做图示、流程图、架构图、画板，选 WHITEBOARD。
                """
        );
        return ALLOWED_ARTIFACTS.contains(choice) ? List.of(choice) : List.of("DOC");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
