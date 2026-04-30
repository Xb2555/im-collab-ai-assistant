package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.domain.Task;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateStrategyResolverTests {

    private final DocumentTemplateStrategyResolver resolver = new DocumentTemplateStrategyResolver();

    @Test
    void shouldResolveHarnessArchitectureTaskToTechnicalArchitecture() {
        Task task = Task.builder()
                .rawInstruction("请分析当前项目的 harness 模块架构，并整理场景 c 的文档生成链路")
                .clarifiedInstruction("聚焦 im 协同助手的 harness 执行编排模块，输出架构设计文档")
                .taskBrief("harness 架构设计")
                .build();

        assertThat(resolver.resolve(task)).isEqualTo(DocumentTemplateType.TECHNICAL_ARCHITECTURE);
    }

    @Test
    void shouldResolveRequirementsTaskToRequirementsTemplate() {
        Task task = Task.builder()
                .rawInstruction("输出 PRD 需求文档")
                .clarifiedInstruction("整理产品需求与范围")
                .taskBrief("需求分析")
                .build();

        assertThat(resolver.resolve(task)).isEqualTo(DocumentTemplateType.REQUIREMENTS);
    }
}
