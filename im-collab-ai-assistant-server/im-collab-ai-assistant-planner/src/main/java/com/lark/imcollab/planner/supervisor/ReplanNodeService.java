package com.lark.imcollab.planner.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.facade.DocumentArtifactIterationFacade;
import com.lark.imcollab.common.facade.DocumentEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.dto.DocumentArtifactIterationRequest;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.DocumentEditIntent;
import com.lark.imcollab.common.model.entity.PendingArtifactCandidate;
import com.lark.imcollab.common.model.entity.PendingArtifactSelection;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskInputContext;
import com.lark.imcollab.common.model.entity.TaskIntakeState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.DocumentArtifactIterationStatus;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;
import com.lark.imcollab.common.model.vo.PresentationIterationVO;
import com.lark.imcollab.planner.replan.PlanAdjustmentInterpreter;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.replan.PlanPatchOperation;
import com.lark.imcollab.planner.service.PlanQualityService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import com.lark.imcollab.planner.service.TaskRuntimeService;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReplanNodeService {

    private static final Logger log = LoggerFactory.getLogger(ReplanNodeService.class);
    private static final Pattern FEISHU_AT_TAG = Pattern.compile("<at\\b[^>]*>.*?</at>", Pattern.CASE_INSENSITIVE);
    private static final Pattern FEISHU_MENTION_TOKEN = Pattern.compile("@_user_\\d+");
    private static final Pattern SINGLE_DIGIT_SELECTION = Pattern.compile("(?<!\\d)([1-5])(?!\\d)");

    private final PlannerSessionService sessionService;
    private final PlanAdjustmentInterpreter adjustmentInterpreter;
    private final PlannerPatchTool patchTool;
    private final PlannerQuestionTool questionTool;
    private final PlanningNodeService planningNodeService;
    private final PlanQualityService qualityService;
    private final PlannerStateStore stateStore;
    private final TaskRuntimeService taskRuntimeService;
    private final ObjectProvider<PresentationEditIntentFacade> presentationEditIntentFacadeProvider;
    private final ObjectProvider<PresentationIterationFacade> presentationIterationFacadeProvider;
    private final ObjectProvider<DocumentEditIntentFacade> documentEditIntentFacadeProvider;
    private final ObjectProvider<DocumentArtifactIterationFacade> documentArtifactIterationFacadeProvider;
    private final ObjectMapper objectMapper;

    public ReplanNodeService(
            PlannerSessionService sessionService,
            PlanAdjustmentInterpreter adjustmentInterpreter,
            PlannerPatchTool patchTool,
            PlannerQuestionTool questionTool,
            PlanningNodeService planningNodeService,
            PlanQualityService qualityService,
            PlannerStateStore stateStore,
            TaskRuntimeService taskRuntimeService,
            ObjectProvider<PresentationEditIntentFacade> presentationEditIntentFacadeProvider,
            ObjectProvider<PresentationIterationFacade> presentationIterationFacadeProvider,
            ObjectProvider<DocumentEditIntentFacade> documentEditIntentFacadeProvider,
            ObjectProvider<DocumentArtifactIterationFacade> documentArtifactIterationFacadeProvider,
            ObjectMapper objectMapper
    ) {
        this.sessionService = sessionService;
        this.adjustmentInterpreter = adjustmentInterpreter;
        this.patchTool = patchTool;
        this.questionTool = questionTool;
        this.planningNodeService = planningNodeService;
        this.qualityService = qualityService;
        this.stateStore = stateStore;
        this.taskRuntimeService = taskRuntimeService;
        this.presentationEditIntentFacadeProvider = presentationEditIntentFacadeProvider;
        this.presentationIterationFacadeProvider = presentationIterationFacadeProvider;
        this.documentEditIntentFacadeProvider = documentEditIntentFacadeProvider;
        this.documentArtifactIterationFacadeProvider = documentArtifactIterationFacadeProvider;
        this.objectMapper = objectMapper;
    }

    public PlanTaskSession replan(String taskId, String adjustmentInstruction, WorkspaceContext workspaceContext) {
        PlanTaskSession session = sessionService.getOrCreate(taskId);
        taskRuntimeService.appendUserIntervention(taskId, adjustmentInstruction);
        PlanTaskSession artifactSelectionResume = resumePendingArtifactSelectionIfNeeded(
                session,
                adjustmentInstruction,
                workspaceContext
        );
        if (artifactSelectionResume != null) {
            return artifactSelectionResume;
        }
        if (hasText(extractTargetArtifactId(adjustmentInstruction))) {
            session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
            return replanCompletedTask(session, adjustmentInstruction, workspaceContext);
        }
        if (isCompletedArtifactContext(taskId, session)) {
            session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
            return replanCompletedTask(session, adjustmentInstruction, workspaceContext);
        }
        if (session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED) {
            return replanCompletedTask(session, adjustmentInstruction, workspaceContext);
        }
        if (session.getPlanBlueprint() == null) {
            return planningNodeService.plan(taskId, adjustmentInstruction, workspaceContext, null);
        }
        PlanPatchIntent patchIntent = adjustmentInterpreter.interpret(session, adjustmentInstruction, workspaceContext);
        if (patchIntent == null || patchIntent.getOperation() == null
                || patchIntent.getOperation() == PlanPatchOperation.CLARIFY_REQUIRED) {
            questionTool.askUser(session, List.of(firstNonBlank(
                    patchIntent == null ? null : patchIntent.getClarificationQuestion(),
                    "我先不改计划。你想新增、删除、改写，还是调整某一步？")));
            return sessionService.get(taskId);
        }
        if (patchIntent.getOperation() == PlanPatchOperation.REGENERATE_ALL) {
            return planningNodeService.plan(taskId, adjustmentInstruction, workspaceContext, adjustmentInstruction);
        }
        session.setClarifiedInstruction(appendSupplement(
                firstNonBlank(session.getClarifiedInstruction(), session.getRawInstruction()),
                adjustmentInstruction
        ));
        String beforeSignature = visiblePlanSignature(session.getPlanBlueprint());
        int beforeCardCount = activeCardCount(session.getPlanBlueprint());
        log.info("Planner replan patch selected task={} operation={} confidence={} targetCards={} newDrafts={} reason={}",
                taskId,
                patchIntent.getOperation(),
                patchIntent.getConfidence(),
                patchIntent.getTargetCardIds() == null ? 0 : patchIntent.getTargetCardIds().size(),
                patchIntent.getNewCardDrafts() == null ? 0 : patchIntent.getNewCardDrafts().size(),
                patchIntent.getReason());
        PlanBlueprint merged = patchTool.merge(session.getPlanBlueprint(), patchIntent, taskId);
        int afterCardCount = activeCardCount(merged);
        log.info("Planner replan merge result task={} operation={} beforeCards={} afterCards={}",
                taskId,
                patchIntent.getOperation(),
                beforeCardCount,
                afterCardCount);
        if (Objects.equals(beforeSignature, visiblePlanSignature(merged))
                || (patchIntent.getOperation() == PlanPatchOperation.ADD_STEP
                && afterCardCount <= beforeCardCount)) {
            questionTool.askUser(session, List.of("我理解你想调整计划，但这次没有形成新的步骤变化。你可以再说具体一点：想新增、删除、修改，还是调整顺序？"));
            return sessionService.get(taskId);
        }
        qualityService.applyMergedPlanAdjustment(session, merged, patchIntent.getReason());
        sessionService.saveWithoutVersionChange(session);
        return session;
    }

    private PlanTaskSession resumePendingArtifactSelectionIfNeeded(
            PlanTaskSession session,
            String instruction,
            WorkspaceContext workspaceContext
    ) {
        TaskIntakeState intakeState = session == null ? null : session.getIntakeState();
        PendingArtifactSelection selection = intakeState == null ? null : intakeState.getPendingArtifactSelection();
        if (selection == null) {
            return null;
        }
        if (selection.getExpiresAt() != null && selection.getExpiresAt().isBefore(Instant.now())) {
            intakeState.setPendingArtifactSelection(null);
            sessionService.saveWithoutVersionChange(session);
            return askCompletedAdjustmentQuestion(
                    session,
                    firstNonBlank(selection.getOriginalInstruction(), instruction),
                    "这个产物选择已经过期了。你可以重新说一下要修改哪个产物。"
            );
        }
        Integer index = parseCandidateIndex(instruction);
        List<PendingArtifactCandidate> candidates = selection.getCandidates() == null ? List.of() : selection.getCandidates();
        if (index == null || index < 1 || index > candidates.size()) {
            return pendingArtifactSelectionReply(
                    session,
                    selection,
                    "我还没识别出要选哪一个产物，请直接回复候选产物前面的编号。"
            );
        }
        PendingArtifactCandidate candidate = candidates.get(index - 1);
        intakeState.setPendingArtifactSelection(null);
        intakeState.setPendingAdjustmentInstruction(null);
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        sessionService.saveWithoutVersionChange(session);
        String resumedInstruction = appendTargetArtifact(selection.getOriginalInstruction(), candidate.getArtifactId());
        return replanCompletedTask(session, resumedInstruction, workspaceContext);
    }

    private PlanTaskSession pendingArtifactSelectionReply(
            PlanTaskSession session,
            PendingArtifactSelection selection,
            String reply
    ) {
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(reply);
        intakeState.setPendingArtifactSelection(selection);
        intakeState.setPendingAdjustmentInstruction(selection.getOriginalInstruction());
        session.setIntakeState(intakeState);
        session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
        sessionService.saveWithoutVersionChange(session);
        return session;
    }

    private String appendTargetArtifact(String instruction, String artifactId) {
        String safeInstruction = instruction == null ? "" : instruction.trim();
        if (!hasText(artifactId)) {
            return safeInstruction;
        }
        return safeInstruction + "\n目标产物ID：" + artifactId;
    }

    private Integer parseCandidateIndex(String input) {
        if (!hasText(input)) {
            return null;
        }
        String normalized = FEISHU_AT_TAG.matcher(input).replaceAll(" ");
        normalized = FEISHU_MENTION_TOKEN.matcher(normalized).replaceAll(" ").trim();
        if (normalized.matches("\\d+")) {
            return Integer.parseInt(normalized);
        }
        Matcher matcher = SINGLE_DIGIT_SELECTION.matcher(normalized);
        Integer selection = null;
        while (matcher.find()) {
            if (selection != null) {
                return null;
            }
            selection = Integer.parseInt(matcher.group(1));
        }
        if (selection != null) {
            return selection;
        }
        return switch (normalized) {
            case "一", "第一个" -> 1;
            case "二", "第二个" -> 2;
            case "三", "第三个" -> 3;
            case "四", "第四个" -> 4;
            case "五", "第五个" -> 5;
            default -> null;
        };
    }

    private boolean isCompletedArtifactContext(String taskId, PlanTaskSession session) {
        if (session != null && session.getPlanningPhase() == PlanningPhaseEnum.COMPLETED) {
            return true;
        }
        if (!hasText(taskId)) {
            return false;
        }
        if (session != null && session.getPlanningPhase() == PlanningPhaseEnum.EXECUTING) {
            return false;
        }
        return !editableArtifacts(taskId, false, false).isEmpty();
    }

    private PlanTaskSession replanCompletedTask(
            PlanTaskSession session,
            String adjustmentInstruction,
            WorkspaceContext workspaceContext
    ) {
        String instruction = normalize(adjustmentInstruction);
        String taskId = session.getTaskId();
        if (wantsNewArtifact(instruction) || wantsOverallReplan(instruction)) {
            return planningNodeService.plan(taskId, instruction, workspaceContext, instruction);
        }

        boolean asksPpt = containsAny(instruction, "ppt", "演示稿", "幻灯片");
        boolean asksDoc = containsAny(instruction, "文档", "doc");
        List<ArtifactRecord> allEditableArtifacts = editableArtifacts(taskId, false, false);
        String targetArtifactId = extractTargetArtifactId(instruction);
        Optional<ArtifactRecord> targetArtifact = findArtifactById(allEditableArtifacts, targetArtifactId);
        if (hasText(targetArtifactId) && targetArtifact.isEmpty()) {
            return askCompletedAdjustmentQuestion(session, instruction, "我没找到你指定的产物 ID。你可以从已有产物列表里重新选择要修改的产物。");
        }
        if (targetArtifact.isEmpty()) {
            List<ArtifactRecord> candidates = editableArtifacts(taskId, asksPpt, asksDoc);
            if (candidates.size() > 1) {
                return askArtifactSelection(session, instruction, workspaceContext, candidates);
            }
            if (candidates.size() == 1) {
                targetArtifact = Optional.of(candidates.get(0));
            }
        }
        if (targetArtifact.isPresent()) {
            return editExistingArtifact(session, targetArtifact.get(), instruction, workspaceContext);
        }
        return askCompletedAdjustmentQuestion(
                session,
                instruction,
                "这个任务已经完成并有现有产物。你是要直接修改现有产物，还是保留现有产物再新建一版？"
        );
    }

    private PlanTaskSession editExistingArtifact(
            PlanTaskSession session,
            ArtifactRecord artifact,
            String instruction,
            WorkspaceContext workspaceContext
    ) {
        if (artifact.getType() == ArtifactTypeEnum.PPT) {
            if (!hasConcretePptEditInstruction(instruction)) {
                return askCompletedAdjustmentQuestion(
                        session,
                        instruction,
                        "可以改现有 PPT。你可以一次说明一个或多个页面要怎么改，比如“把第2页标题改成采购风险与建议，再把第3页正文改成里程碑、风险、预算”。"
                );
            }
            return editExistingPpt(session, artifact, instruction, workspaceContext);
        }
        if (artifact.getType() == ArtifactTypeEnum.DOC) {
            if (!hasConcreteDocEditInstruction(instruction)) {
                return askCompletedAdjustmentQuestion(
                        session,
                        instruction,
                        "可以改现有文档。你想补哪一段、改哪个章节，或插入什么内容？比如“在 1.2 后补充一段风险分析”。"
                );
            }
            return editExistingDoc(session, artifact, instruction, workspaceContext);
        }
        return askCompletedAdjustmentQuestion(
                session,
                instruction,
                "这个产物类型暂不支持原地修改。你可以保留现有产物，再重新生成一版。"
        );
    }

    private PlanTaskSession editExistingPpt(
            PlanTaskSession session,
            ArtifactRecord artifact,
            String instruction,
            WorkspaceContext workspaceContext
    ) {
        PresentationIterationFacade presentationIterationFacade = presentationIterationFacadeProvider == null
                ? null
                : presentationIterationFacadeProvider.getIfAvailable();
        if (presentationIterationFacade == null) {
            return askCompletedAdjustmentQuestion(session, instruction, "PPT 原地编辑能力当前不可用。你可以保留现有 PPT，再重新生成一版。");
        }
        String taskId = session.getTaskId();
        markRuntimeStatus(taskId, TaskStatusEnum.EXECUTING);
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.STEP_STARTED, "开始修改已有 PPT");
        PresentationIterationVO result;
        try {
            result = presentationIterationFacade.edit(PresentationIterationRequest.builder()
                    .taskId(taskId)
                    .artifactId(artifact.getArtifactId())
                    .presentationId(parsePresentationId(artifact.getUrl()))
                    .presentationUrl(artifact.getUrl())
                    .instruction(instruction)
                    .operatorOpenId(workspaceContext == null ? inputSender(session) : workspaceContext.getSenderOpenId())
                    .build());
        } catch (RuntimeException exception) {
            markRuntimeStatus(taskId, TaskStatusEnum.COMPLETED);
            appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.STEP_FAILED,
                    "PPT 修改失败：" + exception.getMessage());
            throw exception;
        }

        artifact.setPreview(firstNonBlank(result.getSummary(), artifact.getPreview()));
        artifact.setStatus("UPDATED");
        artifact.setVersion(artifact.getVersion() + 1);
        artifact.setUpdatedAt(Instant.now());
        stateStore.saveArtifact(artifact);
        markRuntimeStatus(taskId, TaskStatusEnum.COMPLETED);
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.ARTIFACT_UPDATED, firstNonBlank(result.getSummary(), "PPT 已更新"));
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.TASK_COMPLETED, "PPT 原地修改完成");
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(firstNonBlank(result.getSummary(), "PPT 已更新"));
        intakeState.setPendingArtifactSelection(null);
        intakeState.setPendingAdjustmentInstruction(null);
        session.setIntakeState(intakeState);
        session.setTransitionReason("Scenario D PPT artifact updated in place by planner replan");
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(taskId, "COMPLETED");
        return session;
    }

    private PlanTaskSession editExistingDoc(
            PlanTaskSession session,
            ArtifactRecord artifact,
            String instruction,
            WorkspaceContext workspaceContext
    ) {
        DocumentArtifactIterationFacade documentArtifactIterationFacade = documentArtifactIterationFacadeProvider == null
                ? null
                : documentArtifactIterationFacadeProvider.getIfAvailable();
        if (documentArtifactIterationFacade == null) {
            return askCompletedAdjustmentQuestion(session, instruction, "Doc 原地编辑能力当前不可用。你可以保留现有文档，再重新生成一版。");
        }
        String taskId = session.getTaskId();
        markRuntimeStatus(taskId, TaskStatusEnum.EXECUTING);
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.STEP_STARTED, "开始修改已有文档");
        DocumentArtifactIterationResult result;
        try {
            result = documentArtifactIterationFacade.edit(DocumentArtifactIterationRequest.builder()
                    .taskId(taskId)
                    .artifactId(artifact.getArtifactId())
                    .docUrl(artifact.getUrl())
                    .instruction(instruction)
                    .operatorOpenId(workspaceContext == null ? inputSender(session) : workspaceContext.getSenderOpenId())
                    .workspaceContext(workspaceContext)
                    .build());
        } catch (RuntimeException exception) {
            markRuntimeStatus(taskId, TaskStatusEnum.COMPLETED);
            appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.STEP_FAILED,
                    "文档修改失败：" + exception.getMessage());
            throw exception;
        }
        return applyDocumentIterationResult(session, artifact, instruction, result);
    }

    private PlanTaskSession askCompletedAdjustmentQuestion(
            PlanTaskSession session,
            String instruction,
            String question
    ) {
        TaskIntakeState intakeState = session.getIntakeState() == null ? TaskIntakeState.builder().build() : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(question);
        intakeState.setPendingAdjustmentInstruction(instruction);
        clearPendingDocumentApproval(intakeState);
        intakeState.setLastUserMessage(instruction);
        session.setIntakeState(intakeState);
        questionTool.askUser(session, List.of(question));
        PlanTaskSession updated = sessionService.get(session.getTaskId());
        if (updated != null && updated.getIntakeState() != null) {
            updated.getIntakeState().setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
            updated.getIntakeState().setAssistantReply(question);
            updated.getIntakeState().setPendingAdjustmentInstruction(instruction);
            clearPendingDocumentApproval(updated.getIntakeState());
            updated.getIntakeState().setLastUserMessage(instruction);
            updated.setTransitionReason("Completed task adjustment needs user input");
            sessionService.saveWithoutVersionChange(updated);
            return updated;
        }
        return session;
    }

    private PlanTaskSession askArtifactSelection(
            PlanTaskSession session,
            String instruction,
            WorkspaceContext workspaceContext,
            List<ArtifactRecord> candidates
    ) {
        String question = buildArtifactSelectionQuestion(candidates);
        TaskIntakeState intakeState = session.getIntakeState() == null ? TaskIntakeState.builder().build() : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setAssistantReply(question);
        intakeState.setPendingAdjustmentInstruction(instruction);
        intakeState.setPendingArtifactSelection(PendingArtifactSelection.builder()
                .conversationKey(conversationKey(workspaceContext, session))
                .taskId(session.getTaskId())
                .originalInstruction(instruction)
                .candidates(candidates.stream().map(this::toArtifactCandidate).toList())
                .expiresAt(Instant.now().plusSeconds(600))
                .build());
        intakeState.setLastUserMessage(instruction);
        session.setIntakeState(intakeState);
        questionTool.askUser(session, List.of(question));
        PlanTaskSession updated = sessionService.get(session.getTaskId());
        if (updated != null && updated.getIntakeState() != null) {
            updated.getIntakeState().setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
            updated.getIntakeState().setAssistantReply(question);
            updated.getIntakeState().setPendingAdjustmentInstruction(instruction);
            updated.getIntakeState().setPendingArtifactSelection(intakeState.getPendingArtifactSelection());
            updated.getIntakeState().setLastUserMessage(instruction);
            updated.setTransitionReason("Completed task artifact selection needs user input");
            sessionService.saveWithoutVersionChange(updated);
            return updated;
        }
        sessionService.saveWithoutVersionChange(session);
        return session;
    }

    private List<ArtifactRecord> editableArtifacts(String taskId, boolean asksPpt, boolean asksDoc) {
        return stateStore.findArtifactsByTaskId(taskId).stream()
                .filter(artifact -> artifact != null && hasText(artifact.getUrl()))
                .filter(artifact -> artifact.getType() == ArtifactTypeEnum.PPT || artifact.getType() == ArtifactTypeEnum.DOC)
                .filter(artifact -> {
                    if (asksPpt && !asksDoc) {
                        return artifact.getType() == ArtifactTypeEnum.PPT;
                    }
                    if (asksDoc && !asksPpt) {
                        return artifact.getType() == ArtifactTypeEnum.DOC;
                    }
                    return true;
                })
                .sorted(Comparator.comparing((ArtifactRecord artifact) ->
                        artifact.getUpdatedAt() == null ? Instant.EPOCH : artifact.getUpdatedAt()).reversed())
                .toList();
    }

    private Optional<ArtifactRecord> findArtifactById(List<ArtifactRecord> artifacts, String artifactId) {
        if (!hasText(artifactId)) {
            return Optional.empty();
        }
        return artifacts.stream()
                .filter(artifact -> artifactId.equalsIgnoreCase(artifact.getArtifactId()))
                .findFirst();
    }

    private String extractTargetArtifactId(String instruction) {
        if (!hasText(instruction)) {
            return "";
        }
        for (String line : instruction.split("\\R")) {
            String trimmed = line.trim();
            int cnIndex = trimmed.indexOf("目标产物ID：");
            if (cnIndex >= 0) {
                return trimmed.substring(cnIndex + "目标产物ID：".length()).trim();
            }
            int asciiIndex = trimmed.toLowerCase(Locale.ROOT).indexOf("targetartifactid:");
            if (asciiIndex >= 0) {
                return trimmed.substring(asciiIndex + "targetartifactid:".length()).trim();
            }
        }
        return "";
    }

    private PendingArtifactCandidate toArtifactCandidate(ArtifactRecord artifact) {
        return PendingArtifactCandidate.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .type(artifact.getType())
                .title(artifact.getTitle())
                .url(artifact.getUrl())
                .status(artifact.getStatus())
                .version(artifact.getVersion())
                .updatedAt(artifact.getUpdatedAt())
                .build();
    }

    private String buildArtifactSelectionQuestion(List<ArtifactRecord> candidates) {
        StringBuilder builder = new StringBuilder("这个任务下有多个可修改产物，你想修改哪一个？");
        for (int i = 0; i < candidates.size(); i++) {
            ArtifactRecord artifact = candidates.get(i);
            builder.append("\n").append(i + 1).append(". ");
            if (artifact.getType() != null) {
                builder.append("[").append(artifact.getType().name()).append("] ");
            }
            builder.append(firstNonBlank(artifact.getTitle(), artifact.getArtifactId(), "未命名产物"));
            if (hasText(artifact.getArtifactId())) {
                builder.append(" ").append(shortId(artifact.getArtifactId()));
            }
        }
        builder.append("\n回复编号即可。");
        return builder.toString();
    }

    private String conversationKey(WorkspaceContext workspaceContext, PlanTaskSession session) {
        if (workspaceContext != null && hasText(workspaceContext.getChatId())) {
            String threadId = hasText(workspaceContext.getThreadId()) ? workspaceContext.getThreadId() : "_";
            return firstNonBlank(workspaceContext.getInputSource(), "UNKNOWN") + ":"
                    + workspaceContext.getChatId() + ":" + threadId;
        }
        TaskInputContext inputContext = session == null ? null : session.getInputContext();
        if (inputContext == null || !hasText(inputContext.getChatId())) {
            return null;
        }
        String threadId = hasText(inputContext.getThreadId()) ? inputContext.getThreadId() : "_";
        return firstNonBlank(inputContext.getInputSource(), "UNKNOWN") + ":" + inputContext.getChatId() + ":" + threadId;
    }

    private String shortId(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.length() <= 8 ? value : value.substring(0, 8);
    }

    private void markRuntimeStatus(String taskId, TaskStatusEnum status) {
        stateStore.findTask(taskId).ifPresent(task -> {
            task.setStatus(status);
            task.setCurrentStage(status.name());
            task.setNeedUserAction(false);
            task.setUpdatedAt(Instant.now());
            if (status == TaskStatusEnum.COMPLETED) {
                task.setProgress(100);
            }
            stateStore.saveTask(task);
        });
    }

    private void appendRuntimeEvent(String taskId, int version, TaskEventTypeEnum type, Object payload) {
        stateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .type(type)
                .payloadJson(toJson(payload))
                .version(version)
                .createdAt(Instant.now())
                .build());
    }

    private boolean wantsNewArtifact(String instruction) {
        return containsAny(instruction, "保留", "另做", "新建", "再生成", "再做一份", "新版", "新版本");
    }

    private boolean wantsOverallReplan(String instruction) {
        return containsAny(instruction, "整体", "重新规划", "重排计划", "调整计划", "修改计划", "步骤", "流程");
    }

    private boolean hasConcretePptEditInstruction(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        PresentationEditIntentFacade facade = presentationEditIntentFacadeProvider == null
                ? null
                : presentationEditIntentFacadeProvider.getIfAvailable();
        if (facade == null) {
            return false;
        }
        PresentationEditIntent intent = facade.resolve(instruction);
        return intent != null && !intent.isClarificationNeeded();
    }

    private boolean hasConcreteDocEditInstruction(String instruction) {
        if (!hasText(instruction)) {
            return false;
        }
        DocumentEditIntentFacade facade = documentEditIntentFacadeProvider == null
                ? null
                : documentEditIntentFacadeProvider.getIfAvailable();
        if (facade == null) {
            return false;
        }
        DocumentEditIntent intent = facade.resolve(instruction);
        return intent != null && !intent.isClarificationNeeded();
    }

    private PlanTaskSession applyDocumentIterationResult(
            PlanTaskSession session,
            ArtifactRecord artifact,
            String instruction,
            DocumentArtifactIterationResult result
    ) {
        String taskId = session.getTaskId();
        DocumentArtifactIterationStatus status = result == null ? DocumentArtifactIterationStatus.FAILED : result.getStatus();
        TaskIntakeState intakeState = session.getIntakeState() == null
                ? TaskIntakeState.builder().build()
                : session.getIntakeState();
        intakeState.setIntakeType(TaskIntakeTypeEnum.PLAN_ADJUSTMENT);
        intakeState.setPendingArtifactSelection(null);
        intakeState.setPendingAdjustmentInstruction(instruction);
        intakeState.setLastUserMessage(instruction);
        session.setIntakeState(intakeState);

        if (status == DocumentArtifactIterationStatus.COMPLETED) {
            artifact.setPreview(firstNonBlank(result.getPreview(), result.getSummary(), artifact.getPreview()));
            artifact.setStatus("UPDATED");
            artifact.setVersion(artifact.getVersion() + 1);
            artifact.setUpdatedAt(Instant.now());
            stateStore.saveArtifact(artifact);
            markRuntimeStatus(taskId, TaskStatusEnum.COMPLETED);
            appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.ARTIFACT_UPDATED,
                    firstNonBlank(result.getSummary(), "文档已更新"));
            appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.TASK_COMPLETED, "文档原地修改完成");
            clearPendingDocumentApproval(intakeState);
            intakeState.setAssistantReply(firstNonBlank(result.getSummary(), "文档已更新"));
            intakeState.setPendingAdjustmentInstruction(null);
            session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
            session.setTransitionReason("Scenario D DOC artifact updated in place by planner replan");
            sessionService.saveWithoutVersionChange(session);
            sessionService.publishEvent(taskId, "COMPLETED");
            return session;
        }

        if (status == DocumentArtifactIterationStatus.WAITING_APPROVAL) {
            markRuntimeStatus(taskId, TaskStatusEnum.WAITING_APPROVAL);
            intakeState.setAssistantReply(firstNonBlank(result.getSummary(), "文档修改等待确认"));
            intakeState.setPendingDocumentIterationTaskId(result.getTaskId());
            intakeState.setPendingDocumentArtifactId(artifact.getArtifactId());
            intakeState.setPendingDocumentDocUrl(artifact.getUrl());
            intakeState.setPendingDocumentApprovalSummary(result.getSummary());
            intakeState.setPendingDocumentApprovalMode("COMPLETED_TASK_DOC_APPROVAL");
            session.setPlanningPhase(PlanningPhaseEnum.ASK_USER);
            session.setTransitionReason("Completed DOC adjustment is waiting approval");
            sessionService.saveWithoutVersionChange(session);
            sessionService.publishEvent(taskId, "ASK_USER");
            return session;
        }

        markRuntimeStatus(taskId, TaskStatusEnum.COMPLETED);
        appendRuntimeEvent(taskId, session.getVersion(), TaskEventTypeEnum.STEP_FAILED,
                firstNonBlank(result == null ? null : result.getSummary(), "文档修改失败"));
        clearPendingDocumentApproval(intakeState);
        intakeState.setAssistantReply(firstNonBlank(result == null ? null : result.getSummary(), "文档修改失败"));
        session.setPlanningPhase(PlanningPhaseEnum.COMPLETED);
        session.setTransitionReason("Completed DOC adjustment failed");
        sessionService.saveWithoutVersionChange(session);
        sessionService.publishEvent(taskId, "COMPLETED");
        return session;
    }

    private void clearPendingDocumentApproval(TaskIntakeState intakeState) {
        if (intakeState == null) {
            return;
        }
        intakeState.setPendingDocumentIterationTaskId(null);
        intakeState.setPendingDocumentArtifactId(null);
        intakeState.setPendingDocumentDocUrl(null);
        intakeState.setPendingDocumentApprovalSummary(null);
        intakeState.setPendingDocumentApprovalMode(null);
    }

    private String parsePresentationId(String url) {
        if (!hasText(url)) {
            return null;
        }
        int index = url.indexOf("/slides/");
        if (index < 0) {
            return null;
        }
        String token = url.substring(index + "/slides/".length());
        int end = token.indexOf('?');
        if (end >= 0) {
            token = token.substring(0, end);
        }
        end = token.indexOf('/');
        return end >= 0 ? token.substring(0, end) : token;
    }

    private String inputSender(PlanTaskSession session) {
        TaskInputContext context = session == null ? null : session.getInputContext();
        return context == null ? null : context.getSenderOpenId();
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return String.valueOf(payload);
        }
    }

    private String visiblePlanSignature(PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (UserPlanCard card : blueprint.getPlanCards()) {
            if (card == null || "SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                continue;
            }
            builder.append(card.getCardId()).append('|')
                    .append(card.getType()).append('|')
                    .append(card.getTitle()).append('|')
                    .append(card.getDescription()).append(';');
        }
        return builder.toString();
    }

    private int activeCardCount(PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null) {
            return 0;
        }
        int count = 0;
        for (UserPlanCard card : blueprint.getPlanCards()) {
            if (card != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                count++;
            }
        }
        return count;
    }

    private String appendSupplement(String base, String supplement) {
        String safeBase = normalize(base);
        String safeSupplement = normalize(supplement);
        if (safeSupplement.isBlank() || safeBase.contains(safeSupplement)) {
            return safeBase;
        }
        if (safeBase.isBlank()) {
            return safeSupplement;
        }
        return safeBase + "\n补充说明：" + safeSupplement;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean containsAny(String text, String... needles) {
        if (!hasText(text) || needles == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && normalized.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
