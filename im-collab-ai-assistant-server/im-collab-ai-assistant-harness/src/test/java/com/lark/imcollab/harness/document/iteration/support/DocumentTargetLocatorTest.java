package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentTargetLocatorTest {

    @Test
    void exactTextWithMultipleOccurrencesIsRejected() {
        LarkDocTool tool = mock(LarkDocTool.class);
        when(tool.fetchDocFullMarkdown("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("这里有重复句子。这里有重复句子。")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(tool, new DocumentStructureParser());

        assertThatThrownBy(() -> locator.locate(artifact(), "把“这里有重复句子。”改掉"))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("命中多处");
    }

    private Artifact artifact() {
        return Artifact.builder()
                .documentId("doc123")
                .externalUrl("https://example.feishu.cn/docx/doc123")
                .build();
    }
}
