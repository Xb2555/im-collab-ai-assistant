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
}
