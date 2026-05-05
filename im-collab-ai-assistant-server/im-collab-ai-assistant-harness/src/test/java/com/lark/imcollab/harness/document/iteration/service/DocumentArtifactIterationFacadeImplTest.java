package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.model.dto.DocumentArtifactIterationRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;
import com.lark.imcollab.common.model.vo.DocumentIterationPlanVO;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentArtifactIterationFacadeImplTest {

    private final DocumentIterationExecutionService executionService = mock(DocumentIterationExecutionService.class);
    private final DocumentArtifactIterationFacadeImpl facade = new DocumentArtifactIterationFacadeImpl(executionService);

    @Test
    void editMapsCompletedResult() {
        when(executionService.execute(any())).thenReturn(DocumentIterationVO.builder()
                .taskId("iter-1")
                .planningPhase("COMPLETED")
                .summary("文档已更新")
                .preview("更新后的摘要")
                .docUrl("https://example.feishu.cn/docx/doc-1")
                .modifiedBlocks(List.of("blk-1", "blk-2"))
                .build());

        DocumentArtifactIterationResult result = facade.edit(DocumentArtifactIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-doc-1")
                .docUrl("https://example.feishu.cn/docx/doc-1")
                .instruction("补充风险分析")
                .build());

        assertThat(result.getTaskId()).isEqualTo("iter-1");
        assertThat(result.getArtifactId()).isEqualTo("artifact-doc-1");
        assertThat(result.getDocUrl()).isEqualTo("https://example.feishu.cn/docx/doc-1");
        assertThat(result.getStatus()).isEqualTo(DocumentArtifactIterationStatus.COMPLETED);
        assertThat(result.getSummary()).isEqualTo("文档已更新");
        assertThat(result.getPreview()).isEqualTo("更新后的摘要");
        assertThat(result.getModifiedBlocks()).containsExactly("blk-1", "blk-2");
    }

    @Test
    void editMapsWaitingApprovalResult() {
        when(executionService.execute(any())).thenReturn(DocumentIterationVO.builder()
                .taskId("iter-2")
                .planningPhase("WAITING_APPROVAL")
                .requireInput(true)
                .summary("待确认修改计划")
                .editPlan(DocumentIterationPlanVO.builder()
                        .targetPreview("在 1.2 后新增风险提示")
                        .generatedContent("风险提示内容")
                        .riskLevel(DocumentRiskLevel.HIGH)
                        .build())
                .build());

        DocumentArtifactIterationResult result = facade.edit(DocumentArtifactIterationRequest.builder()
                .taskId("task-1")
                .artifactId("artifact-doc-1")
                .docUrl("https://example.feishu.cn/docx/doc-1")
                .instruction("在1.2后补充风险提示")
                .build());

        assertThat(result.getStatus()).isEqualTo(DocumentArtifactIterationStatus.WAITING_APPROVAL);
        assertThat(result.isRequireInput()).isTrue();
        assertThat(result.getApprovalPayload()).isNotNull();
        assertThat(result.getApprovalPayload().getTargetPreview()).isEqualTo("在 1.2 后新增风险提示");
        assertThat(result.getApprovalPayload().getGeneratedContent()).isEqualTo("风险提示内容");
        assertThat(result.getApprovalPayload().getRiskLevel()).isEqualTo(DocumentRiskLevel.HIGH);
    }

    @Test
    void decideMapsUnknownPhaseToFailedAndPreservesFallbackDocUrl() {
        when(executionService.decide(eq("iter-3"), any(DocumentIterationApprovalRequest.class), eq("ou-user")))
                .thenReturn(DocumentIterationVO.builder()
                        .taskId("iter-3")
                        .planningPhase("ASK_USER")
                        .summary("无法继续处理")
                        .build());

        DocumentArtifactIterationResult result = facade.decide(
                "iter-3",
                "artifact-doc-1",
                "https://example.feishu.cn/docx/doc-1",
                DocumentIterationApprovalRequest.builder()
                        .action("MODIFY")
                        .feedback("请缩小改动范围")
                        .build(),
                "ou-user"
        );

        assertThat(result.getStatus()).isEqualTo(DocumentArtifactIterationStatus.FAILED);
        assertThat(result.getDocUrl()).isEqualTo("https://example.feishu.cn/docx/doc-1");
        assertThat(result.getSummary()).isEqualTo("无法继续处理");
        verify(executionService).decide(eq("iter-3"), any(DocumentIterationApprovalRequest.class), eq("ou-user"));
    }
}
