package com.lark.imcollab.planner.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.DocumentArtifactIterationFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.dto.DocumentArtifactIterationRequest;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.vo.DocumentArtifactApprovalPayload;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;
import com.lark.imcollab.common.model.vo.PresentationIterationVO;
import com.lark.imcollab.planner.replan.CardPlanPatchMerger;
import com.lark.imcollab.planner.replan.PlanAdjustmentInterpreter;
import com.lark.imcollab.planner.replan.PlanPatchCardDraft;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.replan.PlanPatchOperation;
import com.lark.imcollab.planner.service.PlanQualityService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplanNodeServiceTest {

    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final PlanAdjustmentInterpreter adjustmentInterpreter = mock(PlanAdjustmentInterpreter.class);
    private final PlannerPatchTool patchTool = new PlannerPatchTool(new CardPlanPatchMerger());
    private final PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
    private final PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
    private final PlanQualityService qualityService = mock(PlanQualityService.class);
    private final PlannerStateStore stateStore = mock(PlannerStateStore.class);
    private final TaskRuntimeService taskRuntimeService = mock(TaskRuntimeService.class);
    private final PresentationIterationFacade presentationIterationFacade = mock(PresentationIterationFacade.class);
    private final DocumentArtifactIterationFacade documentArtifactIterationFacade = mock(DocumentArtifactIterationFacade.class);
    private final ReplanNodeService service = new ReplanNodeService(
            sessionService,
            adjustmentInterpreter,
            patchTool,
            questionTool,
            planningNodeService,
            qualityService,
            stateStore,
            taskRuntimeService,
            provider(presentationIterationFacade),
            provider(documentArtifactIterationFacade),
            new ObjectMapper()
    );

    @Test
    void addStepPatchPersistsMergedPlanCards() {
        PlanTaskSession session = session();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session, "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.ADD_STEP)
                        .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                                .title("生成群内项目进展摘要")
                                .description("生成一段可以直接发到群里的项目进展摘要")
                                .type(PlanCardTypeEnum.SUMMARY)
                                .build()))
                        .confidence(0.92d)
                        .reason("add group progress summary")
                        .build());
        doAnswer(invocation -> {
            PlanTaskSession target = invocation.getArgument(0);
            PlanBlueprint merged = invocation.getArgument(1);
            target.setPlanBlueprint(merged);
            target.setPlanCards(merged.getPlanCards());
            target.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
            return target;
        }).when(qualityService).applyMergedPlanAdjustment(any(), any(), anyString());

        PlanTaskSession result = service.replan("task-1", "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null);

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .containsExactly("生成技术方案文档（含Mermaid架构图）", "基于技术方案文档生成汇报PPT初稿", "生成群内项目进展摘要");
        assertThat(result.getClarifiedInstruction()).contains("最后输出一段可以直接发到群里的项目进展摘要");
        verify(questionTool, never()).askUser(any(), any());
    }

    @Test
    void dependencyOnlyChangeDoesNotPretendPlanAdjusted() {
        PlanTaskSession session = session();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(sessionService.get("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session, "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.ADD_STEP)
                        .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                                .title("生成群内项目进展摘要")
                                .type(PlanCardTypeEnum.SUMMARY)
                                .build()))
                        .confidence(0.9d)
                        .build());
        PlannerPatchTool dependencyOnlyPatchTool = mock(PlannerPatchTool.class);
        when(dependencyOnlyPatchTool.merge(any(), any(), anyString())).thenReturn(PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档（含Mermaid架构图）", PlanCardTypeEnum.DOC, List.of()),
                        card("card-002", "基于技术方案文档生成汇报PPT初稿", PlanCardTypeEnum.PPT, List.of("card-001"))
                ))
                .build());
        ReplanNodeService dependencyOnlyService = new ReplanNodeService(
                sessionService,
                adjustmentInterpreter,
                dependencyOnlyPatchTool,
                questionTool,
                planningNodeService,
                qualityService,
                stateStore,
                taskRuntimeService,
                provider(presentationIterationFacade),
                provider(documentArtifactIterationFacade),
                new ObjectMapper()
        );

        dependencyOnlyService.replan("task-1", "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null);

        verify(questionTool).askUser(any(), any());
        verify(qualityService, never()).applyMergedPlanAdjustment(any(), any(), anyString());
    }

    @Test
    void completedVaguePptEditAsksClarificationInPlannerChain() {
        PlanTaskSession session = completedSession();
        ArtifactRecord artifact = pptArtifact();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(artifact));

        PlanTaskSession result = service.replan("task-1", "修改一下 PPT", null);

        assertThat(result.getIntakeState().getPendingAdjustmentInstruction()).isEqualTo("修改一下 PPT");
        assertThat(result.getIntakeState().getAssistantReply()).contains("你想改哪一页");
        verify(questionTool).askUser(eq(session), any());
        verify(planningNodeService, never()).plan(anyString(), anyString(), any(), any());
        verify(presentationIterationFacade, never()).edit(any());
    }

    @Test
    void completedConcretePptEditUpdatesExistingArtifactWithoutPlanningNewTask() {
        PlanTaskSession session = completedSession();
        ArtifactRecord artifact = pptArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(artifact));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token")
                .summary("已将第2页标题改成新标题")
                .modifiedSlides(List.of("2"))
                .build());

        PlanTaskSession result = service.replan("task-1", "把第2页标题改成新标题", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(artifact.getStatus()).isEqualTo("UPDATED");
        assertThat(artifact.getVersion()).isEqualTo(2);
        assertThat(artifact.getPreview()).isEqualTo("已将第2页标题改成新标题");
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已将第2页标题改成新标题");
        verify(presentationIterationFacade).edit(any(PresentationIterationRequest.class));
        verify(stateStore).saveArtifact(artifact);
        verify(planningNodeService, never()).plan(anyString(), anyString(), any(), any());
        verify(sessionService).publishEvent("task-1", "COMPLETED");
    }

    @Test
    void completedConcretePptEditWithMultiplePptsAsksArtifactSelection() {
        PlanTaskSession session = completedSession();
        ArtifactRecord first = pptArtifact("artifact-ppt-1", "旧版 PPT", Instant.parse("2026-05-04T09:00:00Z"));
        ArtifactRecord second = pptArtifact("artifact-ppt-2", "新版 PPT", Instant.parse("2026-05-04T10:00:00Z"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(first, second));

        PlanTaskSession result = service.replan("task-1", "把第2页标题改成新标题", null);

        assertThat(result.getIntakeState().getAssistantReply()).contains("多个可修改产物");
        assertThat(result.getIntakeState().getPendingArtifactSelection().getCandidates())
                .extracting(candidate -> candidate.getArtifactId())
                .containsExactly("artifact-ppt-2", "artifact-ppt-1");
        verify(presentationIterationFacade, never()).edit(any());
        verify(planningNodeService, never()).plan(anyString(), anyString(), any(), any());
    }

    @Test
    void pendingArtifactSelectionReplyCanResumeInsideReplanNode() {
        PlanTaskSession session = completedSession();
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        session.setIntakeState(TaskIntakeState.builder()
                .intakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT)
                .pendingArtifactSelection(PendingArtifactSelection.builder()
                        .taskId("task-1")
                        .originalInstruction("把第2页标题改成新标题")
                        .candidates(List.of(
                                artifactCandidate("artifact-ppt-2", "新版 PPT"),
                                artifactCandidate("artifact-ppt-1", "旧版 PPT")
                        ))
                        .expiresAt(Instant.now().plusSeconds(600))
                        .build())
                .build());
        ArtifactRecord first = pptArtifact("artifact-ppt-1", "旧版 PPT", Instant.parse("2026-05-04T09:00:00Z"));
        ArtifactRecord second = pptArtifact("artifact-ppt-2", "新版 PPT", Instant.parse("2026-05-04T10:00:00Z"));
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(first, second));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token-1")
                .summary("已将旧版 PPT 第2页标题改成新标题")
                .modifiedSlides(List.of("2"))
                .build());

        service.replan("task-1", "<at user_id=\"bot\">飞书IM- test</at> 2", null);

        ArgumentCaptor<PresentationIterationRequest> request = forClass(PresentationIterationRequest.class);
        verify(presentationIterationFacade).edit(request.capture());
        assertThat(request.getValue().getArtifactId()).isEqualTo("artifact-ppt-1");
        assertThat(session.getIntakeState().getPendingArtifactSelection()).isNull();
        verify(adjustmentInterpreter, never()).interpret(any(), anyString(), any());
    }

    @Test
    void completedConcretePptEditUsesTargetArtifactIdWhenProvided() {
        PlanTaskSession session = completedSession();
        ArtifactRecord first = pptArtifact("artifact-ppt-1", "旧版 PPT", Instant.parse("2026-05-04T09:00:00Z"));
        ArtifactRecord second = pptArtifact("artifact-ppt-2", "新版 PPT", Instant.parse("2026-05-04T10:00:00Z"));
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(first, second));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token-1")
                .summary("已将旧版 PPT 第2页标题改成新标题")
                .modifiedSlides(List.of("2"))
                .build());

        service.replan("task-1", "把第2页标题改成新标题\n目标产物ID：artifact-ppt-1", null);

        ArgumentCaptor<PresentationIterationRequest> request = forClass(PresentationIterationRequest.class);
        verify(presentationIterationFacade).edit(request.capture());
        assertThat(request.getValue().getArtifactId()).isEqualTo("artifact-ppt-1");
        assertThat(first.getStatus()).isEqualTo("UPDATED");
        assertThat(second.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void completedConcreteDocEditUpdatesExistingArtifact() {
        PlanTaskSession session = completedSession();
        ArtifactRecord doc = docArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(doc));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(documentArtifactIterationFacade.edit(any(DocumentArtifactIterationRequest.class))).thenReturn(DocumentArtifactIterationResult.builder()
                .taskId("doc-iter-1")
                .artifactId("artifact-doc-1")
                .docUrl(doc.getUrl())
                .status(DocumentArtifactIterationStatus.COMPLETED)
                .summary("已补充风险分析章节")
                .preview("已补充风险分析章节")
                .build());

        PlanTaskSession result = service.replan("task-1", "把文档补充风险提示\n目标产物ID：artifact-doc-1", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已补充风险分析章节");
        assertThat(doc.getStatus()).isEqualTo("UPDATED");
        verify(documentArtifactIterationFacade).edit(any(DocumentArtifactIterationRequest.class));
        verify(presentationIterationFacade, never()).edit(any());
    }

    @Test
    void completedDocEditWaitingApprovalStoresPendingApprovalContext() {
        PlanTaskSession session = completedSession();
        ArtifactRecord doc = docArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(doc));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(documentArtifactIterationFacade.edit(any(DocumentArtifactIterationRequest.class))).thenReturn(DocumentArtifactIterationResult.builder()
                .taskId("doc-iter-1")
                .artifactId("artifact-doc-1")
                .docUrl(doc.getUrl())
                .status(DocumentArtifactIterationStatus.WAITING_APPROVAL)
                .summary("已生成待确认的文档修改计划")
                .approvalPayload(DocumentArtifactApprovalPayload.builder()
                        .riskLevel(DocumentRiskLevel.MEDIUM)
                        .targetPreview("在 1.2 后新增风险提示")
                        .generatedContent("风险提示内容")
                        .build())
                .build());

        PlanTaskSession result = service.replan("task-1", "在1.2后补充风险提示\n目标产物ID：artifact-doc-1", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingDocumentIterationTaskId()).isEqualTo("doc-iter-1");
        assertThat(result.getIntakeState().getPendingDocumentArtifactId()).isEqualTo("artifact-doc-1");
        assertThat(result.getIntakeState().getPendingDocumentDocUrl()).isEqualTo(doc.getUrl());
        assertThat(result.getIntakeState().getPendingDocumentApprovalMode()).isEqualTo("COMPLETED_TASK_DOC_APPROVAL");
    }

    @Test
    void runtimeCompletedDocTaskPrefersCompletedArtifactEditEvenWhenSessionPhaseIsPlanReady() {
        PlanTaskSession session = session();
        session.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
        session.setInputContext(TaskInputContext.builder().senderOpenId("ou-user").build());
        ArtifactRecord doc = docArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(doc));
        when(documentArtifactIterationFacade.edit(any(DocumentArtifactIterationRequest.class))).thenReturn(DocumentArtifactIterationResult.builder()
                .taskId("doc-iter-1")
                .artifactId("artifact-doc-1")
                .docUrl(doc.getUrl())
                .status(DocumentArtifactIterationStatus.COMPLETED)
                .summary("已补充 IM 完成态文档修改验证")
                .preview("已补充 IM 完成态文档修改验证")
                .build());

        PlanTaskSession result = service.replan("task-1", "把这份文档在 2.2 验证结论末尾补充一句：IM-DOC-ONCE-UNIQUE-CHECK-001。", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已补充 IM 完成态文档修改验证");
        verify(documentArtifactIterationFacade).edit(any(DocumentArtifactIterationRequest.class));
        verify(adjustmentInterpreter, never()).interpret(any(), anyString(), any());
    }

    @Test
    void completedOverallAdjustmentReusesPlanningNode() {
        PlanTaskSession session = completedSession();
        PlanTaskSession replanned = session();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(planningNodeService.plan("task-1", "整体重新规划一下任务步骤", null, "整体重新规划一下任务步骤"))
                .thenReturn(replanned);

        PlanTaskSession result = service.replan("task-1", "整体重新规划一下任务步骤", null);

        assertThat(result).isSameAs(replanned);
        verify(planningNodeService).plan("task-1", "整体重新规划一下任务步骤", null, "整体重新规划一下任务步骤");
        verify(presentationIterationFacade, never()).edit(any());
    }

    private static PlanTaskSession session() {
        PlanBlueprint blueprint = PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档（含Mermaid架构图）", PlanCardTypeEnum.DOC, List.of()),
                        card("card-002", "基于技术方案文档生成汇报PPT初稿", PlanCardTypeEnum.PPT, List.of())
                ))
                .build();
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planBlueprint(blueprint)
                .planCards(blueprint.getPlanCards())
                .build();
    }

    private static PlanTaskSession completedSession() {
        PlanTaskSession session = session();
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        session.setInputContext(TaskInputContext.builder().senderOpenId("ou-user").build());
        return session;
    }

    private static ArtifactRecord pptArtifact() {
        return pptArtifact("artifact-ppt-1", "采购评审 PPT", Instant.now());
    }

    private static ArtifactRecord pptArtifact(String artifactId, String title, Instant updatedAt) {
        return ArtifactRecord.builder()
                .artifactId(artifactId)
                .taskId("task-1")
                .type(ArtifactTypeEnum.PPT)
                .title(title)
                .url("https://example.feishu.cn/slides/" + artifactId)
                .preview("旧标题")
                .status("COMPLETED")
                .version(1)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }

    private static PendingArtifactCandidate artifactCandidate(String artifactId, String title) {
        return PendingArtifactCandidate.builder()
                .artifactId(artifactId)
                .taskId("task-1")
                .type(ArtifactTypeEnum.PPT)
                .title(title)
                .url("https://example.feishu.cn/slides/" + artifactId)
                .version(1)
                .updatedAt(Instant.now())
                .build();
    }

    private static UserPlanCard card(String cardId, String title, PlanCardTypeEnum type, List<String> dependsOn) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .title(title)
                .description(title)
                .type(type)
                .status("PENDING")
                .dependsOn(dependsOn)
                .build();
    }

    private static ArtifactRecord docArtifact() {
        return ArtifactRecord.builder()
                .artifactId("artifact-doc-1")
                .taskId("task-1")
                .type(ArtifactTypeEnum.DOC)
                .title("采购评审文档")
                .url("https://example.feishu.cn/docx/doc-token")
                .preview("旧文档摘要")
                .status("COMPLETED")
                .version(1)
                .updatedAt(Instant.parse("2026-05-04T10:00:00Z"))
                .build();
    }

    private static <T> ObjectProvider<T> provider(T facade) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return facade;
            }

            @Override
            public T getIfAvailable() {
                return facade;
            }

            @Override
            public T getIfUnique() {
                return facade;
            }

            @Override
            public T getObject() {
                return facade;
            }

            @Override
            public Iterator<T> iterator() {
                return List.of(facade).iterator();
            }

            @Override
            public Stream<T> stream() {
                return Stream.of(facade);
            }

            @Override
            public Stream<T> orderedStream() {
                return stream();
            }
        };
    }
}
