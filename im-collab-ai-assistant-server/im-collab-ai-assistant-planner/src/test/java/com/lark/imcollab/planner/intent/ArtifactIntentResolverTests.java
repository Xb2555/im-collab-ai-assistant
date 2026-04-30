package com.lark.imcollab.planner.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactIntentResolverTests {

    @Test
    void shouldResolveMultipleArtifactsByRule() {
        ArtifactIntentResolver resolver = new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "DOC");

        List<String> artifacts = resolver.resolveArtifacts("请先生成技术文档，再补一份 PPT 汇报");

        assertThat(artifacts).containsExactly("DOC", "PPT");
    }

    @Test
    void shouldFallbackToLlmChoiceWhenRulesMiss() {
        ArtifactIntentResolver resolver = new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "WHITEBOARD");

        List<String> artifacts = resolver.resolveArtifacts("帮我整理成一个可视化交付物");

        assertThat(artifacts).containsExactly("WHITEBOARD");
    }
}
