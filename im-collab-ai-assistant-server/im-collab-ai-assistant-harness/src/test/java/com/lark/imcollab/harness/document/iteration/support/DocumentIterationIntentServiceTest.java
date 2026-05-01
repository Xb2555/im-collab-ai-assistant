package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentIterationIntentServiceTest {

    private final DocumentIterationIntentService service = new DocumentIterationIntentService();

    @Test
    void resolvesExplainFirst() {
        assertThat(service.resolve("解释一下这一段是什么意思"))
                .isEqualTo(DocumentIterationIntentType.EXPLAIN);
    }

    @Test
    void resolvesDelete() {
        assertThat(service.resolve("把风险与边界这一节删掉"))
                .isEqualTo(DocumentIterationIntentType.DELETE);
    }

    @Test
    void resolvesInsertMedia() {
        assertThat(service.resolve("在文档里插入图片和附件"))
                .isEqualTo(DocumentIterationIntentType.INSERT_MEDIA);
    }

    @Test
    void resolvesStyleBeforeGenericUpdate() {
        assertThat(service.resolve("把项目背景改成面向发布会的语言风格，更正式一些"))
                .isEqualTo(DocumentIterationIntentType.UPDATE_STYLE);
    }

    @Test
    void fallsBackToUpdateContent() {
        assertThat(service.resolve("把技术方案这部分改一下"))
                .isEqualTo(DocumentIterationIntentType.UPDATE_CONTENT);
    }
}
