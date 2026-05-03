package com.lark.imcollab.harness.summary.service;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.domain.Step;
import com.lark.imcollab.common.domain.StepStatus;
import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.domain.TaskEventType;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.StepRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.harness.document.support.DocumentExecutionSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultSummaryExecutionServiceTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
    private final StepRepository stepRepository = mock(StepRepository.class);
    private final TaskEventRepository eventRepository = mock(TaskEventRepository.class);
    private final DocumentExecutionSupport executionSupport = mock(DocumentExecutionSupport.class);
    private final ChatModel chatModel = mock(ChatModel.class);
    private final DefaultSummaryExecutionService service = new DefaultSummaryExecutionService(
            taskRepository,
            artifactRepository,
            stepRepository,
            eventRepository,
            executionSupport,
            chatModel
    );

    @Test
    void summaryUsesWholeTaskContextAndStoresPlainImText() {
        Task task = Task.builder()
                .taskId("task-1")
                .status(TaskStatus.COMPLETED)
                .rawInstruction("总结本次任务上下文")
                .clarifiedInstruction("输出一段可直接发群的项目进展摘要")
                .taskBrief("项目进展摘要")
                .executionContract(ExecutionContract.builder()
                        .allowedArtifacts(List.of("SUMMARY"))
                        .constraints(List.of("不要生成文档和PPT"))
                        .sourceScope(WorkspaceContext.builder()
                                .selectedMessages(List.of("完成 Planner 上下文拉取", "Slides 授权偶发失败"))
                                .docRefs(List.of("https://example.feishu.cn/docx/doc-1"))
                                .build())
                        .build())
                .build();
        when(taskRepository.findById("task-1")).thenReturn(Optional.of(task));
        when(executionSupport.findSummaryStepId("task-1")).thenReturn(Optional.of("summary-step"));
        when(stepRepository.findByTaskId("task-1")).thenReturn(List.of(
                Step.builder()
                        .stepId("doc-step")
                        .taskId("task-1")
                        .name("生成技术方案文档")
                        .status(StepStatus.COMPLETED)
                        .output("已生成技术方案文档")
                        .createdAt(Instant.parse("2026-05-03T01:00:00Z"))
                        .build(),
                Step.builder()
                        .stepId("summary-step")
                        .taskId("task-1")
                        .name("生成任务上下文摘要")
                        .status(StepStatus.RUNNING)
                        .createdAt(Instant.parse("2026-05-03T01:01:00Z"))
                        .build()
        ));
        when(eventRepository.findByTaskId("task-1")).thenReturn(List.of(
                TaskEvent.builder()
                        .taskId("task-1")
                        .stepId("doc-step")
                        .type(TaskEventType.ARTIFACT_CREATED)
                        .payload("技术方案文档已创建")
                        .occurredAt(Instant.parse("2026-05-03T01:02:00Z"))
                        .build(),
                TaskEvent.builder()
                        .taskId("task-1")
                        .stepId("summary-step")
                        .type(TaskEventType.STEP_STARTED)
                        .payload("开始生成任务上下文摘要")
                        .occurredAt(Instant.parse("2026-05-03T01:03:00Z"))
                        .build()
        ));
        when(artifactRepository.findByTaskId("task-1")).thenReturn(List.of(Artifact.builder()
                .type(ArtifactType.DOC_LINK)
                .title("技术方案文档")
                .externalUrl("https://example.feishu.cn/docx/doc-1")
                .build()));
        when(chatModel.call(anyString())).thenReturn("""
                # 项目进展
                - **本周完成** Planner 上下文拉取。
                - **风险** Slides 授权偶发失败。
                """);

        service.execute("task-1");

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel).call(promptCaptor.capture());
        assertThat(promptCaptor.getValue())
                .contains(
                        "完成 Planner 上下文拉取",
                        "Slides 授权偶发失败",
                        "技术方案文档",
                        "不要生成文档和PPT",
                        "当前状态：COMPLETED",
                        "生成技术方案文档，状态：COMPLETED",
                        "ARTIFACT_CREATED@doc-step：技术方案文档已创建"
                );

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(executionSupport).saveArtifact(eq("task-1"), eq("summary-step"), eq(ArtifactType.SUMMARY),
                eq("项目进展摘要"), contentCaptor.capture(), eq(null));
        assertThat(contentCaptor.getValue())
                .contains("本周完成 Planner 上下文拉取", "风险 Slides 授权偶发失败")
                .doesNotContain("#", "- **", "**");
        verify(executionSupport).markSummaryStepCompleted(eq("task-1"), eq(contentCaptor.getValue()));
    }
}
