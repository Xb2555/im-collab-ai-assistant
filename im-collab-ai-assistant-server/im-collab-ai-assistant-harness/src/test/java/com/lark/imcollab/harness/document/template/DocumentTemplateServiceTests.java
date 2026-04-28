package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.entity.DocumentOutline;
import com.lark.imcollab.common.model.entity.DocumentReviewResult;
import com.lark.imcollab.common.model.entity.DocumentSectionDraft;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTemplateServiceTests {

    private final DocumentTemplateService service = new DocumentTemplateService();

    @Test
    void shouldSelectMeetingTemplateFromCardText() {
        UserPlanCard card = UserPlanCard.builder()
                .title("项目会议纪要")
                .description("整理今天的会议结论")
                .type(PlanCardTypeEnum.DOC)
                .build();

        assertThat(service.selectTemplate(card)).isEqualTo(DocumentTemplateType.MEETING_SUMMARY);
    }

    @Test
    void shouldRenderTemplateWithSections() {
        String markdown = service.render(
                DocumentTemplateType.REPORT,
                DocumentOutline.builder().title("季度汇报").build(),
                List.of(
                        DocumentSectionDraft.builder().heading("背景").body("项目进入执行阶段").build(),
                        DocumentSectionDraft.builder().heading("目标").body("完成 MVP 交付").build(),
                        DocumentSectionDraft.builder().heading("方案").body("采用固定 DAG 执行").build(),
                        DocumentSectionDraft.builder().heading("风险").body("依赖待确认").build(),
                        DocumentSectionDraft.builder().heading("分工").body("产品负责需求，研发负责实现").build(),
                        DocumentSectionDraft.builder().heading("时间计划").body("本周完成联调").build()
                ),
                DocumentReviewResult.builder().summary("审阅通过").build(),
                "补充说明"
        );

        assertThat(markdown).contains("审阅通过");
        assertThat(markdown).contains("## 背景");
        assertThat(markdown).contains("固定 DAG 执行");
    }
}
