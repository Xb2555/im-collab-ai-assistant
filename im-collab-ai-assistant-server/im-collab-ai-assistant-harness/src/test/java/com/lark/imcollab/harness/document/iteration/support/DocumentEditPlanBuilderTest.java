package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentEditPlanBuilderTest {

    @Test
    void sectionRewriteStripsDuplicatedHeadingFromModelOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(anyString())).thenReturn("""
                ## 一、项目背景与问题

                ### 1.1 新正文

                管理层版本内容
                """);
        DocumentEditPlanBuilder builder = new DocumentEditPlanBuilder(chatModel);
        DocumentTargetSelector selector = DocumentTargetSelector.builder()
                .targetType(DocumentTargetType.SECTION)
                .locatorStrategy(DocumentLocatorStrategy.BY_HEADING)
                .locatorValue("一、项目背景与问题")
                .matchedExcerpt("旧正文")
                .matchedBlockIds(List.of("heading-block", "body-block"))
                .build();

        DocumentEditPlan plan = builder.build("task-1", DocumentIterationIntentType.UPDATE_STYLE, selector, "改成管理层风格");

        assertThat(plan.getGeneratedContent()).startsWith("### 1.1 新正文");
        assertThat(plan.getGeneratedContent()).doesNotStartWith("## 一、项目背景与问题");
    }
}
