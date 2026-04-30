package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentOutlineSection;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateServiceTests {

    private final DocumentTemplateService service = new DocumentTemplateService();

    @Test
    void shouldNotRepeatTemplateMappedSectionsInDetailedExpansion() {
        String markdown = service.render(
                DocumentTemplateType.TECHNICAL_ARCHITECTURE,
                DocumentOutline.builder()
                        .title("Harness 架构设计")
                        .sections(List.of(
                                DocumentOutlineSection.builder().heading("项目背景").keyPoints(List.of("背景要点")).build(),
                                DocumentOutlineSection.builder().heading("设计目标与非目标").keyPoints(List.of("目标要点")).build(),
                                DocumentOutlineSection.builder().heading("模块分层与职责").keyPoints(List.of("分层要点")).build(),
                                DocumentOutlineSection.builder().heading("风险与边界").keyPoints(List.of("风险要点")).build(),
                                DocumentOutlineSection.builder().heading("演进建议").keyPoints(List.of("建议要点")).build(),
                                DocumentOutlineSection.builder().heading("实施节奏与时间计划").keyPoints(List.of("时间要点")).build(),
                                DocumentOutlineSection.builder().heading("执行流程").keyPoints(List.of("流程要点")).build()
                        ))
                        .build(),
                List.of(
                        DocumentSectionDraft.builder().heading("项目背景").body("背景正文").build(),
                        DocumentSectionDraft.builder().heading("设计目标与非目标").body("目标正文").build(),
                        DocumentSectionDraft.builder().heading("模块分层与职责").body("分层正文").build(),
                        DocumentSectionDraft.builder().heading("风险与边界").body("风险正文").build(),
                        DocumentSectionDraft.builder().heading("演进建议").body("建议正文").build(),
                        DocumentSectionDraft.builder().heading("实施节奏与时间计划").body("时间正文").build(),
                        DocumentSectionDraft.builder().heading("执行流程").body("流程正文").build()
                ),
                DocumentReviewResult.builder().summary("通过").build(),
                "",
                "",
                ""
        );

        assertThat(markdown).contains("## 一、项目背景与问题");
        assertThat(markdown).contains("背景正文");
        assertThat(markdown).contains("## 十、详细设计展开");
        assertThat(markdown).contains("## 执行流程");
        assertThat(markdown).doesNotContain("## 项目背景");
        assertThat(markdown).doesNotContain("## 设计目标与非目标");
        assertThat(markdown).doesNotContain("## 模块分层与职责");
    }

    @Test
    void shouldNotGenerateHardcodedSubsectionNumbersForFallbackContent() {
        String markdown = service.render(
                DocumentTemplateType.REPORT,
                DocumentOutline.builder()
                        .title("季度汇报")
                        .sections(List.of(
                                DocumentOutlineSection.builder().heading("背景").keyPoints(List.of("背景关键点")).build()
                        ))
                        .build(),
                List.of(),
                DocumentReviewResult.builder().summary("通过").build(),
                "",
                "",
                ""
        );

        assertThat(markdown).contains("背景关键点");
        assertThat(markdown).doesNotContain("### 1.1");
        assertThat(markdown).doesNotContain("#### 1.1.1");
    }

    @Test
    void shouldPreferModuleSectionOverPrinciplesForPlanSlot() {
        String markdown = service.render(
                DocumentTemplateType.TECHNICAL_ARCHITECTURE,
                DocumentOutline.builder()
                        .title("Harness 架构设计")
                        .sections(List.of(
                                DocumentOutlineSection.builder().heading("1. 目标与设计原则").keyPoints(List.of("原则要点")).build(),
                                DocumentOutlineSection.builder().heading("3. 总体架构").keyPoints(List.of("总体架构要点")).build(),
                                DocumentOutlineSection.builder().heading("4. 六层详细说明").keyPoints(List.of("模块分层要点")).build()
                        ))
                        .build(),
                List.of(
                        DocumentSectionDraft.builder().heading("1. 目标与设计原则").body("原则正文").build(),
                        DocumentSectionDraft.builder().heading("3. 总体架构").body("总体架构正文").build(),
                        DocumentSectionDraft.builder().heading("4. 六层详细说明").body("模块分层正文").build()
                ),
                DocumentReviewResult.builder().summary("通过").build(),
                "",
                "",
                ""
        );

        assertThat(markdown).contains("## 三、架构原则");
        assertThat(markdown).contains("原则正文");
        assertThat(markdown).contains("## 五、模块分层与职责");
        assertThat(markdown).contains("总体架构正文");
        assertThat(markdown).doesNotContain("## 1. 目标与设计原则");
        assertThat(markdown).doesNotContain("## 3. 总体架构");
    }

    @Test
    void shouldStripOutlineOrdinalsWhenRenderingDetailedSections() {
        String markdown = service.render(
                DocumentTemplateType.TECHNICAL_ARCHITECTURE,
                DocumentOutline.builder()
                        .title("Harness 架构设计")
                        .sections(List.of(
                                DocumentOutlineSection.builder().heading("一、项目背景").keyPoints(List.of("背景要点")).build(),
                                DocumentOutlineSection.builder().heading("九、补充说明").keyPoints(List.of("补充要点")).build()
                        ))
                        .build(),
                List.of(
                        DocumentSectionDraft.builder().heading("一、项目背景").body("背景正文").build(),
                        DocumentSectionDraft.builder().heading("九、补充说明").body("补充正文").build()
                ),
                DocumentReviewResult.builder().summary("通过").build(),
                "",
                "",
                ""
        );

        assertThat(markdown).contains("## 补充说明");
        assertThat(markdown).doesNotContain("## 九、补充说明");
        assertThat(markdown).doesNotContain("\n## 一、项目背景\n");
    }
}
