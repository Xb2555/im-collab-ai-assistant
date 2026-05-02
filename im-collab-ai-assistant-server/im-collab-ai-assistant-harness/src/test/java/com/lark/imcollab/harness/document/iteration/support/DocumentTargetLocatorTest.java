package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.entity.DocumentTargetSelector;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentLocatorStrategy;
import com.lark.imcollab.common.model.enums.DocumentRelativePosition;
import com.lark.imcollab.common.model.enums.DocumentTargetType;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DocumentTargetLocatorTest {

    @Test
    void exactTextWithMultipleOccurrencesIsRejected() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.UPDATE_CONTENT, "把“这里有重复句子。”改掉"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.BY_EXACT_TEXT,
                        DocumentRelativePosition.REPLACE,
                        "这里有重复句子。"
                ));
        when(tool.fetchDocFullMarkdown("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("这里有重复句子。这里有重复句子。")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        assertThatThrownBy(() -> locator.locate(artifact(), DocumentIterationIntentType.UPDATE_CONTENT, "把“这里有重复句子。”改掉"))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("命中多处");
    }

    @Test
    void docStartInsertUsesFirstBlockAsAnchor() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.INSERT, "在文档开头新增章节"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.DOC_START,
                        DocumentRelativePosition.BEFORE,
                        ""
                ));
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h2 id=\"heading-1\">一、项目背景</h2><h2 id=\"heading-2\">二、方案设计</h2>")
                        .build());
        when(tool.fetchDocRangeMarkdown("https://example.feishu.cn/docx/doc123", "heading-1", "heading-1"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("## 一、项目背景")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        DocumentTargetSelector selector = locator.locate(artifact(), DocumentIterationIntentType.INSERT, "在文档开头新增章节");

        assertThat(selector.getLocatorStrategy()).isEqualTo(DocumentLocatorStrategy.DOC_START);
        assertThat(selector.getRelativePosition()).isEqualTo(DocumentRelativePosition.BEFORE);
        assertThat(selector.getTargetType()).isEqualTo(DocumentTargetType.TITLE);
        assertThat(selector.getMatchedBlockIds()).containsExactly("heading-1");
        assertThat(selector.getMatchedExcerpt()).isEqualTo("## 一、项目背景");
        assertThat(selector.getLocatorValue()).isEqualTo("一、项目背景");
    }

    @Test
    void docStartInsertUnwrapsFragmentMarkdown() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.INSERT, "在文档开头新增章节"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.DOC_START,
                        DocumentRelativePosition.BEFORE,
                        ""
                ));
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h1 id=\"heading-1\">二、项目目标</h1>")
                        .build());
        when(tool.fetchDocRangeMarkdown("https://example.feishu.cn/docx/doc123", "heading-1", "heading-1"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("""
                                <fragment mode="range" requested-end="heading-1" requested-start="heading-1">
                                # 二、项目目标
                                </fragment>
                                """)
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        DocumentTargetSelector selector = locator.locate(artifact(), DocumentIterationIntentType.INSERT, "在文档开头新增章节");

        assertThat(selector.getMatchedExcerpt()).isEqualTo("# 二、项目目标");
    }

    @Test
    void docStartInsertPrefersFirstTopLevelHeadingInsteadOfFirstNestedHeading() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.INSERT, "在文档开头新增章节"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.DOC_START,
                        DocumentRelativePosition.BEFORE,
                        ""
                ));
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h3 id=\"heading-1\">2.1 目标</h3><h2 id=\"heading-2\">二、设计目标</h2><h3 id=\"heading-3\">2.2 范围</h3>")
                        .build());
        when(tool.fetchDocRangeMarkdown("https://example.feishu.cn/docx/doc123", "heading-2", "heading-2"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("## 二、设计目标")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        DocumentTargetSelector selector = locator.locate(artifact(), DocumentIterationIntentType.INSERT, "在文档开头新增章节");

        assertThat(selector.getMatchedBlockIds()).containsExactly("heading-2");
        assertThat(selector.getMatchedExcerpt()).isEqualTo("## 二、设计目标");
        assertThat(selector.getLocatorValue()).isEqualTo("二、设计目标");
    }

    @Test
    void insertBeforeHeadingUsesHeadingBlockOnly() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.INSERT, "在项目背景前新增前言"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.BY_HEADING,
                        DocumentRelativePosition.BEFORE,
                        "项目背景"
                ));
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h2 id=\"heading-1\">一、项目背景</h2><h2 id=\"heading-2\">二、方案设计</h2>")
                        .build());
        when(tool.fetchDocRangeMarkdown("https://example.feishu.cn/docx/doc123", "heading-1", "heading-1"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("## 一、项目背景")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        DocumentTargetSelector selector = locator.locate(artifact(), DocumentIterationIntentType.INSERT, "在项目背景前新增前言");

        assertThat(selector.getLocatorStrategy()).isEqualTo(DocumentLocatorStrategy.BY_HEADING);
        assertThat(selector.getRelativePosition()).isEqualTo(DocumentRelativePosition.BEFORE);
        assertThat(selector.getTargetType()).isEqualTo(DocumentTargetType.TITLE);
        assertThat(selector.getLocatorValue()).isEqualTo("一、项目背景");
        assertThat(selector.getMatchedBlockIds()).containsExactly("heading-1");
        assertThat(selector.getMatchedExcerpt()).isEqualTo("## 一、项目背景");
    }

    @Test
    void deleteFirstSubsectionResolvesByModelAgainstCurrentOutline() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.DELETE, "删除第一小节的内容"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.BY_HEADING,
                        DocumentRelativePosition.REPLACE,
                        "第一小节"
                ));
        when(chatModel.call(anyString())).thenReturn("SUBSECTION", "BLOCK_ID=heading-1");
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h1 id=\"heading-0\">一、项目背景</h1><h3 id=\"heading-1\">2.1 目标</h3><h4 id=\"heading-2\">2.1.1 提升组件复用性</h4><h2 id=\"heading-3\">二、项目目标</h2>")
                        .build());
        when(tool.fetchDocSectionMarkdown("https://example.feishu.cn/docx/doc123", "heading-1"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("### 2.1 目标\n\n正文内容")
                        .build());
        when(tool.fetchDocSection("https://example.feishu.cn/docx/doc123", "heading-1", "with-ids"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<doc><h3 id=\"heading-1\">2.1 目标</h3><p id=\"body-1\">正文内容</p></doc>")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        DocumentTargetSelector selector = locator.locate(artifact(), DocumentIterationIntentType.DELETE, "删除第一小节的内容");

        assertThat(selector.getLocatorStrategy()).isEqualTo(DocumentLocatorStrategy.BY_HEADING);
        assertThat(selector.getLocatorValue()).isEqualTo("2.1 目标");
        assertThat(selector.getMatchedBlockIds()).containsExactly("heading-1", "body-1");
        assertThat(selector.getMatchedExcerpt()).isEqualTo("### 2.1 目标\n\n正文内容");
    }

    @Test
    void deleteFirstSubsectionReturnsNotFoundWhenOutlineIsAmbiguous() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.DELETE, "删除第一小节的内容"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.BY_HEADING,
                        DocumentRelativePosition.REPLACE,
                        "第一小节"
                ));
        when(chatModel.call(anyString())).thenReturn("BLOCK_ID=NOT_FOUND");
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h1 id=\"heading-0\">一、项目背景</h1><h3 id=\"heading-2\">2.2 非目标</h3><h2 id=\"heading-3\">二、项目目标</h2>")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        assertThatThrownBy(() -> locator.locate(artifact(), DocumentIterationIntentType.DELETE, "删除第一小节的内容"))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("未能定位目标章节");
    }

    @Test
    void deleteFirstSubsectionRejectsTopLevelHeadingEvenIfModelReturnsIt() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.DELETE, "删除第一小节的内容"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.BY_HEADING,
                        DocumentRelativePosition.REPLACE,
                        "第一小节"
                ));
        when(chatModel.call(anyString())).thenReturn("SUBSECTION", "BLOCK_ID=heading-1");
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h2 id=\"heading-1\">二、项目目标</h2><h3 id=\"heading-2\">2.2 非目标</h3><h2 id=\"heading-3\">三、项目架构</h2>")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        assertThatThrownBy(() -> locator.locate(artifact(), DocumentIterationIntentType.DELETE, "删除第一小节的内容"))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("未能定位目标章节");
    }

    @Test
    void deleteWholeThirdChapterRejectsSubsectionEvenIfModelReturnsIt() {
        DocumentAnchorIntentService anchorIntentService = mock(DocumentAnchorIntentService.class);
        LarkDocTool tool = mock(LarkDocTool.class);
        ChatModel chatModel = mock(ChatModel.class);
        when(anchorIntentService.decide(DocumentIterationIntentType.DELETE, "删除第三节整个大章节"))
                .thenReturn(new DocumentAnchorIntentService.AnchorDecision(
                        DocumentLocatorStrategy.BY_HEADING,
                        DocumentRelativePosition.DELETE,
                        "第三节整个大章节"
                ));
        when(chatModel.call(anyString())).thenReturn("TOP_LEVEL", "BLOCK_ID=heading-31");
        when(tool.fetchDocOutline("https://example.feishu.cn/docx/doc123"))
                .thenReturn(LarkDocFetchResult.builder()
                        .content("<h2 id=\"heading-2\">二、项目目标</h2><h3 id=\"heading-22\">2.2 非目标</h3><h2 id=\"heading-3\">三、项目架构</h2><h3 id=\"heading-31\">3.1 单向数据流与状态管理分离</h3>")
                        .build());
        DocumentTargetLocator locator = new DocumentTargetLocator(anchorIntentService, tool, new DocumentStructureParser(), chatModel);

        assertThatThrownBy(() -> locator.locate(artifact(), DocumentIterationIntentType.DELETE, "删除第三节整个大章节"))
                .isInstanceOf(AiAssistantException.class)
                .hasMessageContaining("未能定位目标章节");
    }

    private Artifact artifact() {
        return Artifact.builder()
                .documentId("doc123")
                .externalUrl("https://example.feishu.cn/docx/doc123")
                .build();
    }
}
