package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.model.entity.DocumentStructureSnapshot;
import com.lark.imcollab.skills.lark.doc.LarkDocFetchResult;
import com.lark.imcollab.skills.lark.doc.LarkDocReadGateway;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentStructureSnapshotBuilderTest {

    @Test
    void buildUsesLightweightOutlineOnly() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        when(readGateway.fetchDocOutline("https://example.feishu.cn/docx/doc123")).thenReturn(LarkDocFetchResult.builder()
                .docId("doc123")
                .revisionId(9L)
                .content("""
                        <h2 id="heading-1">一、项目背景</h2>
                        <h2 id="heading-2">二、实施路径</h2>
                        """)
                .build());
        DocumentStructureSnapshotBuilder builder = new DocumentStructureSnapshotBuilder(readGateway, new DocumentStructureParser());

        DocumentStructureSnapshot snapshot = builder.build(Artifact.builder()
                .externalUrl("https://example.feishu.cn/docx/doc123")
                .build());

        assertThat(snapshot.getDocId()).isEqualTo("doc123");
        assertThat(snapshot.getRevisionId()).isEqualTo(9L);
        assertThat(snapshot.getTopLevelSequence()).containsExactly("heading-1", "heading-2");
        assertThat(snapshot.getHeadingPathIndexById()).containsKey("heading-1");
        assertThat(snapshot.getHeadingTitleIndexNormalized()).containsKey("一、项目背景".replaceAll("\\s+", "").toLowerCase());
        assertThat(snapshot.getRawOutlineXml()).contains("项目背景");
        assertThat(snapshot.getRawFullXml()).isNull();
        assertThat(snapshot.getRawFullMarkdown()).isNull();
        verify(readGateway).fetchDocOutline("https://example.feishu.cn/docx/doc123");
        verify(readGateway, never()).fetchDocSection("https://example.feishu.cn/docx/doc123", "heading-1", "with-ids");
    }

    @Test
    void fetchSectionDetailLoadsSectionOnDemandAndCachesResult() {
        LarkDocReadGateway readGateway = mock(LarkDocReadGateway.class);
        when(readGateway.fetchDocOutline("doc123")).thenReturn(LarkDocFetchResult.builder()
                .docId("doc123")
                .revisionId(1L)
                .content("<h2 id=\"heading-1\">一、项目背景</h2>")
                .build());
        when(readGateway.fetchDocSection("doc123", "heading-1", "with-ids")).thenReturn(LarkDocFetchResult.builder()
                .content("""
                        <h2 id="heading-1">一、项目背景</h2>
                        <p id="body-1">正文第一段</p>
                        <p id="body-2">正文第二段</p>
                        """)
                .build());
        DocumentStructureSnapshotBuilder builder = new DocumentStructureSnapshotBuilder(readGateway, new DocumentStructureParser());
        DocumentStructureSnapshot snapshot = builder.build(Artifact.builder().documentId("doc123").build());

        builder.fetchSectionDetail(snapshot, "heading-1", "doc123");
        builder.fetchSectionDetail(snapshot, "heading-1", "doc123");

        assertThat(snapshot.getSectionBlockIds().get("heading-1")).containsExactly("heading-1", "body-1", "body-2");
        assertThat(snapshot.getBlockIndex()).containsKeys("body-1", "body-2");
        assertThat(snapshot.getBlockOrderIndex()).containsKeys("body-1", "body-2");
        verify(readGateway).fetchDocSection("doc123", "heading-1", "with-ids");
    }
}
