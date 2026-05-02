package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TermResolution;
import com.lark.imcollab.planner.intent.ArtifactIntentResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionContractFactoryTests {

    @Test
    void shouldEmbedTermResolutionIntoContract() {
        ExecutionContractFactory factory = new ExecutionContractFactory(
                new ArtifactIntentResolver((instruction, allowedChoices, systemPrompt) -> "DOC"));
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("t1")
                .rawInstruction("帮我写 harness 架构文档")
                .clarifiedInstruction("聚焦当前项目的 harness 模块")
                .termResolutions(List.of(TermResolution.builder()
                        .term("harness")
                        .resolvedMeaning("PROJECT_HARNESS_MODULE")
                        .confidence("HIGH")
                        .build()))
                .build();

        ExecutionContract contract = factory.build(session);

        assertThat(contract.getDomainContext()).contains("PROJECT_HARNESS_MODULE");
        assertThat(contract.getTermResolutions()).hasSize(1);
        assertThat(contract.getClarifiedInstruction()).contains("术语消歧");
    }
}
