package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.ResultVerdictEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LarkIMPlannerReviewNotifierTest {

    private final LarkMessageReplyTool replyTool = mock(LarkMessageReplyTool.class);
    private final LarkOutboundMessageRetryService retryService = mock(LarkOutboundMessageRetryService.class);
    private final LarkIMPlannerReviewNotifier notifier = new LarkIMPlannerReviewNotifier(
            replyTool,
            retryService,
            new LarkIMTaskReplyFormatter()
    );

    @Test
    void sendsPlannerReviewedArtifactsToP2PUser() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("d2f254d0-48b7-4520-a652-a454a291cbdb")
                .inputContext(TaskInputContext.builder()
                        .chatType("p2p")
                        .senderOpenId("ou-user")
                        .build())
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().taskId("task-1").status(TaskStatusEnum.COMPLETED).build())
                .artifacts(List.of(ArtifactRecord.builder()
                        .artifactId("artifact-1")
                        .type(ArtifactTypeEnum.DOC)
                        .title("技术方案文档")
                        .url("https://doc.example")
                        .preview("文档预览")
                        .build()))
                .build();
        TaskResultEvaluation evaluation = TaskResultEvaluation.builder()
                .verdict(ResultVerdictEnum.PASS)
                .build();

        notifier.notifyExecutionReviewed(session, snapshot, evaluation);

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idempotencyKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(
                org.mockito.ArgumentMatchers.eq("ou-user"),
                textCaptor.capture(),
                idempotencyKeyCaptor.capture()
        );
        assertThat(textCaptor.getValue())
                .contains("我检查了一下", "产物", "技术方案文档", "https://doc.example", "任务状态：已完成");
        assertThat(textCaptor.getValue()).doesNotContain("预览：", "文档预览");
        assertThat(idempotencyKeyCaptor.getValue())
                .startsWith("pr-")
                .hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void reviewNoticeOnlyShowsShareableArtifactLinks() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("d2f254d0-48b7-4520-a652-a454a291cbdb")
                .inputContext(TaskInputContext.builder()
                        .chatType("p2p")
                        .senderOpenId("ou-user")
                        .build())
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().taskId("task-1").status(TaskStatusEnum.EXECUTING).build())
                .artifacts(List.of(
                        ArtifactRecord.builder()
                                .artifactId("doc-1")
                                .type(ArtifactTypeEnum.DOC)
                                .title("飞书项目协作方案技术设计文档")
                                .url("https://doc.example")
                                .preview("{\"title\":\"飞书项目协作方案技术设计文档\",\"sections\":[...]}")
                                .build(),
                        ArtifactRecord.builder()
                                .artifactId("diagram-1")
                                .type(ArtifactTypeEnum.DIAGRAM)
                                .title("飞书项目协作方案技术设计文档 图表")
                                .preview("sequenceDiagram\nUser->>DocService: 创建文档")
                                .build()))
                .build();

        notifier.notifyExecutionReviewed(session, snapshot, TaskResultEvaluation.builder()
                .verdict(ResultVerdictEnum.PASS)
                .build());

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(
                org.mockito.ArgumentMatchers.eq("ou-user"),
                textCaptor.capture(),
                org.mockito.ArgumentMatchers.anyString()
        );
        assertThat(textCaptor.getValue())
                .contains("飞书项目协作方案技术设计文档", "https://doc.example", "任务状态：正在执行")
                .doesNotContain("预览：", "sequenceDiagram", "{\"title\"", "图表");
    }

    @Test
    void sendsExecutionFailureNoticeToP2PUser() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("d2f254d0-48b7-4520-a652-a454a291cbdb")
                .inputContext(TaskInputContext.builder()
                        .chatType("p2p")
                        .senderOpenId("ou-user")
                        .build())
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder()
                        .taskId("task-1")
                        .status(TaskStatusEnum.FAILED)
                        .build())
                .build();

        notifier.notifyExecutionFailed(
                session,
                snapshot,
                "Harness execution failed after IM confirmation: Error: server time out error"
        );

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> idempotencyKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(replyTool).sendPrivateText(
                org.mockito.ArgumentMatchers.eq("ou-user"),
                textCaptor.capture(),
                idempotencyKeyCaptor.capture()
        );
        assertThat(textCaptor.getValue())
                .contains("任务执行失败了", "飞书文档创建超时", "暂时没有拿到可展示的产物", "重试", "重新执行");
        assertThat(idempotencyKeyCaptor.getValue())
                .startsWith("pr-")
                .hasSizeLessThanOrEqualTo(50);
    }
}
