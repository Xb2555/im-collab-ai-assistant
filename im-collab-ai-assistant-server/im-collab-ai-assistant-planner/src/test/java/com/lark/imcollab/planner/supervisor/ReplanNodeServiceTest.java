package com.lark.imcollab.planner.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.PlannerContextAcquisitionFacade;
import com.lark.imcollab.common.facade.DocumentArtifactIterationFacade;
import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.dto.DocumentArtifactIterationRequest;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.enums.DocumentAnchorKind;
import com.lark.imcollab.common.model.enums.DocumentAnchorMatchMode;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
import com.lark.imcollab.common.model.enums.DocumentRiskLevel;
import com.lark.imcollab.common.model.enums.DocumentSemanticActionType;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationIterationStatus;
import com.lark.imcollab.common.model.enums.PresentationIterationIntentType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
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
    private final PresentationEditIntentFacade presentationEditIntentFacade = mock(PresentationEditIntentFacade.class);
    private final PresentationIterationFacade presentationIterationFacade = mock(PresentationIterationFacade.class);
    private final DocumentEditIntentFacade documentEditIntentFacade = mock(DocumentEditIntentFacade.class);
    private final DocumentArtifactIterationFacade documentArtifactIterationFacade = mock(DocumentArtifactIterationFacade.class);
    private final PlannerContextAcquisitionFacade plannerContextAcquisitionFacade = mock(PlannerContextAcquisitionFacade.class);
    private final ContextNodeService contextNodeService = mock(ContextNodeService.class);
    private final ReplanNodeService service = new ReplanNodeService(
            sessionService,
            adjustmentInterpreter,
            patchTool,
            questionTool,
            planningNodeService,
            qualityService,
            stateStore,
            taskRuntimeService,
            provider(presentationEditIntentFacade),
            provider(presentationIterationFacade),
            provider(documentEditIntentFacade),
            provider(documentArtifactIterationFacade),
            provider(plannerContextAcquisitionFacade),
            contextNodeService,
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
        verify(questionTool, never()).askUser(any(), any());
    }

    @Test
    void completedTaskKeepExistingCreateNewUsesPatchMergeInsteadOfRebuildingWholePlan() {
        PlanTaskSession session = session();
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session,
                "保留现有文档，基于该文档新增一份汇报PPT初稿。\n产物策略：KEEP_EXISTING_CREATE_NEW",
                null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.ADD_STEP)
                        .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                                .title("基于文档生成汇报PPT初稿")
                                .description("基于现有文档新增一份汇报PPT初稿")
                                .type(PlanCardTypeEnum.PPT)
                                .build()))
                        .confidence(1.0d)
                        .reason("add ppt follow-up")
                        .build());
        doAnswer(invocation -> {
            PlanTaskSession target = invocation.getArgument(0);
            PlanBlueprint merged = invocation.getArgument(1);
            target.setPlanBlueprint(merged);
            target.setPlanCards(merged.getPlanCards());
            target.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
            return target;
        }).when(qualityService).applyMergedPlanAdjustment(any(), any(), anyString());

        PlanTaskSession result = service.replan(
                "task-1",
                "保留现有文档，基于该文档新增一份汇报PPT初稿。\n产物策略：KEEP_EXISTING_CREATE_NEW",
                null
        );

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .containsExactly(
                        "生成技术方案文档（含Mermaid架构图）",
                        "基于技术方案文档生成汇报PPT初稿",
                        "基于文档生成汇报PPT初稿"
                );
        verify(planningNodeService, never()).plan(anyString(), anyString(), any(), anyString());
    }

    @Test
    void replanningTaskWithGeneratedArtifactsDoesNotMistakeInterruptForCompletedArtifactEdit() {
        PlanTaskSession session = session();
        session.setPlanningPhase(PlanningPhaseEnum.REPLANNING);
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.REPLANNING)
                .build()));
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(ArtifactRecord.builder()
                .artifactId("artifact-doc")
                .taskId("task-1")
                .type(ArtifactTypeEnum.DOC)
                .url("https://doc.example")
                .build()));
        when(adjustmentInterpreter.interpret(session, "中断一下", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.CLARIFY_REQUIRED)
                        .clarificationQuestion("我先不改计划。你想新增、删除、改写，还是调整某一步？")
                        .build());
        when(sessionService.get("task-1")).thenReturn(session);

        PlanTaskSession result = service.replan("task-1", "中断一下", null);

        assertThat(result).isSameAs(session);
        verify(questionTool).askUser(eq(session), any());
        verify(documentArtifactIterationFacade, never()).edit(any());
        verify(planningNodeService, never()).plan(anyString(), anyString(), any(), anyString());
    }

    @Test
    void replanningClarificationPreservesResumeExecutionMarker() {
        PlanTaskSession session = session();
        session.setPlanningPhase(PlanningPhaseEnum.REPLANNING);
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session, "中断一下", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.CLARIFY_REQUIRED)
                        .clarificationQuestion("我先不改计划。你想新增、删除、改写，还是调整某一步？")
                        .build());
        PlanTaskSession askUser = session();
        askUser.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        askUser.setIntakeState(TaskIntakeState.builder().build());
        when(sessionService.get("task-1")).thenReturn(askUser);

        PlanTaskSession result = service.replan("task-1", "中断一下", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.ASK_USER);
        assertThat(result.getIntakeState().getPendingInteractionType()).isEqualTo(PendingInteractionTypeEnum.EXECUTING_PLAN_ADJUSTMENT);
        assertThat(result.getIntakeState().getPendingAdjustmentInstruction()).isEqualTo("中断一下");
    }

    @Test
    void replanClearsLegacyExecutionSemanticsBeforeApplyingPatch() {
        PlanTaskSession session = session();
        session.setClarifiedInstruction("旧澄清\n执行约束：标题必须包含 OLD_PLAN_IM_INTERRUPT_20260508");
        session.setClarificationAnswers(List.of("旧回答"));
        session.setExecutionContract(ExecutionContract.builder()
                .taskBrief("旧执行合同")
                .constraints(List.of("标题必须包含 OLD_PLAN_IM_INTERRUPT_20260508"))
                .build());
        session.setIntentSnapshot(IntentSnapshot.builder()
                .constraints(List.of("标题必须包含 OLD_PLAN_IM_INTERRUPT_20260508"))
                .build());
        session.setPlanBlueprint(PlanBlueprint.builder()
                .taskBrief("旧计划")
                .constraints(List.of("标题必须包含 OLD_PLAN_IM_INTERRUPT_20260508"))
                .successCriteria(List.of("旧成功标准"))
                .risks(List.of("旧风险"))
                .planCards(List.of(
                        card("card-001", "旧文档步骤", PlanCardTypeEnum.DOC, List.of())
                ))
                .build());
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session, "把标题改成 78787", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.UPDATE_STEP)
                        .targetCardIds(List.of("card-001"))
                        .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                                .title("生成飞书文档（标题含 78787）")
                                .description("标题必须包含 78787")
                                .type(PlanCardTypeEnum.DOC)
                                .build()))
                        .confidence(1.0d)
                        .reason("update title")
                        .build());
        doAnswer(invocation -> {
            PlanTaskSession target = invocation.getArgument(0);
            PlanBlueprint merged = invocation.getArgument(1);
            target.setPlanBlueprint(merged);
            target.setPlanCards(merged.getPlanCards());
            target.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
            return target;
        }).when(qualityService).applyMergedPlanAdjustment(any(), any(), anyString());

        service.replan("task-1", "把标题改成 78787", null);

        assertThat(session.getClarifiedInstruction()).isNull();
        assertThat(session.getExecutionContract()).isNull();
        assertThat(session.getClarificationAnswers()).isEmpty();
        assertThat(session.getIntentSnapshot().getConstraints()).isEmpty();
        assertThat(session.getPlanBlueprint().getConstraints()).isEmpty();
        assertThat(session.getPlanBlueprint().getSuccessCriteria()).isEmpty();
        assertThat(session.getPlanBlueprint().getRisks()).isEmpty();
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
                provider(presentationEditIntentFacade),
                provider(presentationIterationFacade),
                provider(documentEditIntentFacade),
                provider(documentArtifactIterationFacade),
                provider(plannerContextAcquisitionFacade),
                contextNodeService,
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
        when(presentationEditIntentFacade.resolve(eq("修改一下 PPT"), any())).thenReturn(PresentationEditIntent.builder()
                .clarificationNeeded(true)
                .clarificationHint("请明确要改第几页和改成什么内容")
                .build());

        PlanTaskSession result = service.replan("task-1", "修改一下 PPT", null);

        assertThat(result.getIntakeState().getPendingAdjustmentInstruction()).isEqualTo("修改一下 PPT");
        assertThat(result.getIntakeState().getAssistantReply())
                .contains("可以改现有 PPT")
                .contains("插入一页")
                .contains("删除第3页")
                .contains("移到第2页后");
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
        when(presentationEditIntentFacade.resolve(anyString(), any())).thenReturn(titleIntent(2, "新标题"));
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
    void completedPptEditPartialSuccessStillUpdatesArtifactButWarnsUser() {
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
        when(presentationEditIntentFacade.resolve(anyString())).thenReturn(titleIntent(2, "新标题"));
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token")
                .status(PresentationIterationStatus.PARTIAL_SUCCESS)
                .writeApplied(true)
                .verificationPassed(false)
                .failureReason("PPT update verification failed: target text not applied to resolved node")
                .summary("已将第2页标题改成新标题")
                .modifiedSlides(List.of("2"))
                .build());

        PlanTaskSession result = service.replan("task-1", "把第2页标题改成新标题", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(artifact.getVersion()).isEqualTo(2);
        assertThat(result.getIntakeState().getAssistantReply()).contains("已写入飞书，但校验未完全通过");
        verify(stateStore).saveArtifact(artifact);
        verify(sessionService).publishEvent("task-1", "COMPLETED");
    }

    @Test
    void completedPptEditFailedBeforeWriteDoesNotUpdateArtifactVersion() {
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
        when(presentationEditIntentFacade.resolve(anyString())).thenReturn(titleIntent(2, "新标题"));
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token")
                .status(PresentationIterationStatus.FAILED_BEFORE_WRITE)
                .writeApplied(false)
                .verificationPassed(false)
                .failureReason("页内锚点命中不唯一，请补充更具体的位置")
                .summary("页内锚点命中不唯一，请补充更具体的位置")
                .modifiedSlides(List.of())
                .build());

        PlanTaskSession result = service.replan("task-1", "把第2页标题改成新标题", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(artifact.getVersion()).isEqualTo(1);
        assertThat(result.getIntakeState().getAssistantReply()).contains("命中不唯一");
        verify(stateStore, never()).saveArtifact(artifact);
        verify(sessionService).publishEvent("task-1", "COMPLETED");
    }

    @Test
    void completedQuotedPptEditWithoutPageIndexStillUpdatesExistingArtifact() {
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
        when(presentationEditIntentFacade.resolve("历史文化遗产这一段写的详细一些")).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.EXPAND_ELEMENT)
                        .targetElementType(PresentationTargetElementType.BODY)
                        .anchorMode(PresentationAnchorMode.BY_QUOTED_TEXT)
                        .quotedText("历史文化遗产")
                        .replacementText("历史文化遗产，形成了上海旅游的重要文化吸引力与国际传播名片。")
                        .build()))
                .clarificationNeeded(false)
                .build());
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token")
                .summary("已将“历史文化遗产”这一段扩写")
                .modifiedSlides(List.of("s2"))
                .build());

        PlanTaskSession result = service.replan("task-1", "历史文化遗产这一段写的详细一些", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已将“历史文化遗产”这一段扩写");
        verify(presentationIterationFacade).edit(any(PresentationIterationRequest.class));
        verify(questionTool, never()).askUser(any(), any());
    }

    @Test
    void completedInsertAfterQuotedPptEditUpdatesExistingArtifact() {
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
        when(presentationEditIntentFacade.resolve("在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点")).thenReturn(PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .operations(List.of(PresentationEditOperation.builder()
                        .actionType(PresentationEditActionType.INSERT_AFTER_ELEMENT)
                        .targetElementType(PresentationTargetElementType.BODY)
                        .anchorMode(PresentationAnchorMode.BY_QUOTED_TEXT)
                        .pageIndex(1)
                        .quotedText("文旅融合创新，消费场景丰富多元")
                        .contentInstruction("在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点")
                        .build()))
                .clarificationNeeded(false)
                .build());
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token")
                .summary("已在第 1 页目标段落后插入内容")
                .modifiedSlides(List.of("s1"))
                .build());

        PlanTaskSession result = service.replan("task-1", "在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已在第 1 页目标段落后插入内容");
        verify(presentationIterationFacade).edit(any(PresentationIterationRequest.class));
        verify(questionTool, never()).askUser(any(), any());
    }

    @Test
    void completedConcretePptEditWithMultiplePptsAsksArtifactSelection() {
        PlanTaskSession session = completedSession();
        ArtifactRecord first = pptArtifact("artifact-ppt-1", "旧版 PPT", Instant.parse("2026-05-04T09:00:00Z"));
        ArtifactRecord second = pptArtifact("artifact-ppt-2", "新版 PPT", Instant.parse("2026-05-04T10:00:00Z"));
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(first, second));
        when(presentationEditIntentFacade.resolve(anyString(), any())).thenReturn(titleIntent(2, "新标题"));

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
        when(presentationEditIntentFacade.resolve(anyString(), any())).thenReturn(titleIntent(2, "新标题"));
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
        when(presentationEditIntentFacade.resolve(eq("把第2页标题改成新标题\n目标产物ID：artifact-ppt-1"), any())).thenReturn(titleIntent(2, "新标题"));
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
    void completedNaturalPptTitleEditUsesResolvedIntentWithoutClarification() {
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
        when(presentationEditIntentFacade.resolve(eq("帮我修改第一页标题为7878"), any())).thenReturn(titleIntent(1, "7878"));
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("task-1")
                .artifactId("artifact-ppt-1")
                .presentationId("slides-token")
                .summary("已修改 PPT 第 1 页：7878")
                .modifiedSlides(List.of("1"))
                .build());

        PlanTaskSession result = service.replan("task-1", "帮我修改第一页标题为7878", null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已修改 PPT 第 1 页：7878");
        verify(questionTool, never()).askUser(any(), any());
        verify(presentationIterationFacade).edit(any(PresentationIterationRequest.class));
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
        when(documentEditIntentFacade.resolve(eq("把文档补充风险提示\n目标产物ID：artifact-doc-1"), any()))
                .thenReturn(concreteDocIntent());
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
    void completedDocEditPullsHistoricalMessagesIntoWorkspaceContext() {
        PlanTaskSession session = completedSession();
        session.setInputContext(TaskInputContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_PRIVATE_CHAT")
                .senderOpenId("ou-user")
                .build());
        ArtifactRecord doc = docArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        String instruction = "加一节关于666的内容，拉取前10分钟的消息作为内容总结";
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(doc));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(contextNodeService.check(eq(session), eq("task-1"), eq(instruction), any())).thenReturn(ContextSufficiencyResult.collect(
                com.lark.imcollab.common.model.entity.ContextAcquisitionPlan.builder().needCollection(true).build(),
                "need im history"
        ));
        when(documentEditIntentFacade.resolve(eq(instruction), any())).thenReturn(DocumentEditIntent.builder()
                .intentType(DocumentIterationIntentType.INSERT)
                .semanticAction(DocumentSemanticActionType.APPEND_SECTION_TO_DOCUMENT_END)
                .clarificationNeeded(false)
                .build());
        when(plannerContextAcquisitionFacade.acquire(any(), any(), eq(instruction))).thenReturn(ContextAcquisitionResult.builder()
                .success(true)
                .sufficient(true)
                .selectedMessages(List.of("19:20 风险消息A", "19:25 风险消息B"))
                .selectedMessageIds(List.of("m-1", "m-2"))
                .build());
        when(documentArtifactIterationFacade.edit(any(DocumentArtifactIterationRequest.class))).thenReturn(DocumentArtifactIterationResult.builder()
                .taskId("doc-iter-1")
                .artifactId("artifact-doc-1")
                .docUrl(doc.getUrl())
                .status(DocumentArtifactIterationStatus.COMPLETED)
                .summary("已插入历史消息总结")
                .preview("已插入历史消息总结")
                .build());

        PlanTaskSession result = service.replan("task-1", instruction, null);

        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已插入历史消息总结");
        ArgumentCaptor<DocumentArtifactIterationRequest> request = forClass(DocumentArtifactIterationRequest.class);
        verify(documentArtifactIterationFacade).edit(request.capture());
        assertThat(request.getValue().getWorkspaceContext().getSelectedMessages())
                .containsExactly("19:20 风险消息A", "19:25 风险消息B");
        assertThat(result.getIntentSnapshot().getSourceScope().getSelectedMessages())
                .containsExactly("19:20 风险消息A", "19:25 风险消息B");
        assertThat(result.getPlanBlueprint().getSourceScope().getSelectedMessages())
                .containsExactly("19:20 风险消息A", "19:25 风险消息B");
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
        when(documentEditIntentFacade.resolve(eq("在1.2后补充风险提示\n目标产物ID：artifact-doc-1"), any()))
                .thenReturn(concreteDocIntent());
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
        when(documentEditIntentFacade.resolve(eq("把这份文档在 2.2 验证结论末尾补充一句：IM-DOC-ONCE-UNIQUE-CHECK-001。"), any()))
                .thenReturn(concreteDocIntent());
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
    void completedDocEditUsesStructuredIntentInsteadOfPlannerKeywordGuard() {
        PlanTaskSession session = completedSession();
        ArtifactRecord doc = docArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        String instruction = "在2.2后补一个测试总结，是对回归测试的总结";
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(doc));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(documentEditIntentFacade.resolve(eq(instruction), any())).thenReturn(concreteDocIntent());
        when(documentArtifactIterationFacade.edit(any(DocumentArtifactIterationRequest.class))).thenReturn(DocumentArtifactIterationResult.builder()
                .taskId("doc-iter-1")
                .artifactId("artifact-doc-1")
                .docUrl(doc.getUrl())
                .status(DocumentArtifactIterationStatus.COMPLETED)
                .summary("已补充测试总结")
                .preview("已补充测试总结")
                .build());

        PlanTaskSession result = service.replan("task-1", instruction, null);

        assertThat(result.getPlanningPhase()).isEqualTo(PlanningPhaseEnum.COMPLETED);
        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已补充测试总结");
        verify(documentArtifactIterationFacade).edit(any(DocumentArtifactIterationRequest.class));
        verify(questionTool, never()).askUser(any(), any());
    }

    @Test
    void completedPptEditPullsHistoricalMessagesIntoWorkspaceContext() {
        PlanTaskSession session = completedSession();
        session.setInputContext(TaskInputContext.builder()
                .chatId("chat-1")
                .inputSource("LARK_PRIVATE_CHAT")
                .senderOpenId("ou-user")
                .build());
        ArtifactRecord ppt = pptArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        String instruction = "把最后一页改成最近10分钟关于风险的消息总结";
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(ppt));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(contextNodeService.check(eq(session), eq("task-1"), eq(instruction), any())).thenReturn(ContextSufficiencyResult.collect(
                com.lark.imcollab.common.model.entity.ContextAcquisitionPlan.builder().needCollection(true).build(),
                "need im history"
        ));
        when(presentationEditIntentFacade.resolve(eq(instruction), any()))
                .thenReturn(PresentationEditIntent.builder()
                        .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                        .actionType(PresentationEditActionType.REPLACE_SLIDE_BODY)
                        .pageIndex(2)
                        .replacementText("基于历史消息整理")
                        .clarificationNeeded(false)
                        .build());
        when(plannerContextAcquisitionFacade.acquire(any(), any(), eq(instruction))).thenReturn(ContextAcquisitionResult.builder()
                .success(true)
                .sufficient(true)
                .selectedMessages(List.of("19:20 风险消息A", "19:25 风险消息B"))
                .selectedMessageIds(List.of("m-1", "m-2"))
                .build());
        when(presentationIterationFacade.edit(any(PresentationIterationRequest.class))).thenReturn(PresentationIterationVO.builder()
                .taskId("ppt-iter-1")
                .artifactId("artifact-ppt-1")
                .presentationId("ppt-id")
                .presentationUrl(ppt.getUrl())
                .summary("已更新最后一页")
                .build());

        PlanTaskSession result = service.replan("task-1", instruction, null);

        assertThat(result.getIntakeState().getAssistantReply()).isEqualTo("已更新最后一页");
        ArgumentCaptor<PresentationIterationRequest> request = forClass(PresentationIterationRequest.class);
        verify(presentationIterationFacade).edit(request.capture());
        assertThat(request.getValue().getWorkspaceContext().getSelectedMessages())
                .containsExactly("19:20 风险消息A", "19:25 风险消息B");
        assertThat(result.getIntentSnapshot().getSourceScope().getSelectedMessages())
                .containsExactly("19:20 风险消息A", "19:25 风险消息B");
        assertThat(result.getPlanBlueprint().getSourceScope().getSelectedMessages())
                .containsExactly("19:20 风险消息A", "19:25 风险消息B");
    }

    @Test
    void unclearDocEditStillAsksClarification() {
        PlanTaskSession session = completedSession();
        ArtifactRecord doc = docArtifact();
        TaskRecord task = TaskRecord.builder()
                .taskId("task-1")
                .status(TaskStatusEnum.COMPLETED)
                .progress(100)
                .build();
        String instruction = "帮我调整一下这个文档";
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(stateStore.findArtifactsByTaskId("task-1")).thenReturn(List.of(doc));
        when(stateStore.findTask("task-1")).thenReturn(Optional.of(task));
        when(documentEditIntentFacade.resolve(eq(instruction), any())).thenReturn(DocumentEditIntent.builder()
                .userInstruction(instruction)
                .clarificationNeeded(true)
                .clarificationHint("请明确改哪个章节")
                .build());

        PlanTaskSession result = service.replan("task-1", instruction, null);

        assertThat(result.getIntakeState().getAssistantReply()).contains("可以改现有文档");
        verify(documentArtifactIterationFacade, never()).edit(any(DocumentArtifactIterationRequest.class));
    }

    private DocumentEditIntent concreteDocIntent() {
        return DocumentEditIntent.builder()
                .intentType(DocumentIterationIntentType.INSERT)
                .semanticAction(DocumentSemanticActionType.INSERT_BLOCK_AFTER_ANCHOR)
                .anchorSpec(com.lark.imcollab.common.model.entity.DocumentAnchorSpec.builder()
                        .anchorKind(DocumentAnchorKind.SECTION)
                        .matchMode(DocumentAnchorMatchMode.BY_HEADING_NUMBER)
                        .headingNumber("2.2")
                        .build())
                .clarificationNeeded(false)
                .build();
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

    private static PresentationEditIntent titleIntent(int pageIndex, String replacementText) {
        return PresentationEditIntent.builder()
                .intentType(PresentationIterationIntentType.UPDATE_CONTENT)
                .actionType(PresentationEditActionType.REPLACE_SLIDE_TITLE)
                .pageIndex(pageIndex)
                .replacementText(replacementText)
                .clarificationNeeded(false)
                .build();
    }
}
