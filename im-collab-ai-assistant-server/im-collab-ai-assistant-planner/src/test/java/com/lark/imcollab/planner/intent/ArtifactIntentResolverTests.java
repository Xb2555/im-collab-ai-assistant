package com.lark.imcollab.planner.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactIntentResolverTests {

    @Test
    void shouldResolveMultipleArtifactsByLlmChoice() {
        ArtifactIntentResolver resolver = new ArtifactIntentResolver(new LlmChoiceResolver() {
            @Override
            public String chooseOne(String instruction, List<String> allowedChoices, String systemPrompt) {
                return "";
            }

            @Override
            public List<String> chooseMany(String instruction, List<String> allowedChoices, String systemPrompt) {
                return List.of("DOC", "PPT");
            }
        });

        List<String> artifacts = resolver.resolveArtifacts("请先生成技术文档，再补一份 PPT 汇报");

        assertThat(artifacts).containsExactly("DOC", "PPT");
    }

    @Test
    void shouldFallbackToLlmChoiceWhenRulesMiss() {
        ArtifactIntentResolver resolver = new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "WHITEBOARD");

        List<String> artifacts = resolver.resolveArtifacts("帮我整理成一个可视化交付物");

        assertThat(artifacts).containsExactly("WHITEBOARD");
    }

    @Test
    void shouldNotInferArtifactFromKeywordWithoutLlmChoice() {
        ArtifactIntentResolver resolver = new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "");

        List<String> artifacts = resolver.resolveArtifacts("帮我写一份标题叫PPT性能分析的文档");

        assertThat(artifacts).containsExactly("DOC");
    }
}
