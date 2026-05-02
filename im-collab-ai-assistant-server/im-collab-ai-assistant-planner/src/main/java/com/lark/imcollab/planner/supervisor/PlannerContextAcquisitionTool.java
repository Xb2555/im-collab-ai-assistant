package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.facade.PlannerContextAcquisitionFacade;
import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.planner.service.PlannerConversationMemoryService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PlannerContextAcquisitionTool {

    private final ObjectProvider<PlannerContextAcquisitionFacade> acquisitionFacadeProvider;
    private final PlannerSessionService sessionService;
    private final PlannerConversationMemoryService memoryService;
    private final PlannerRuntimeTool runtimeTool;

    public PlannerContextAcquisitionTool(
            ObjectProvider<PlannerContextAcquisitionFacade> acquisitionFacadeProvider,
            PlannerSessionService sessionService,
            PlannerConversationMemoryService memoryService,
            PlannerRuntimeTool runtimeTool
    ) {
        this.acquisitionFacadeProvider = acquisitionFacadeProvider;
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.runtimeTool = runtimeTool;
    }

    @Tool(description = "Scenario B: acquire missing task context from approved sources such as IM history or Lark docs.")
    public ContextAcquisitionResult acquireContext(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            ContextAcquisitionPlan plan
    ) {
        PlannerContextAcquisitionFacade facade = acquisitionFacadeProvider.getIfAvailable();
        if (facade == null) {
            return ContextAcquisitionResult.failure("上下文读取能力暂不可用。");
        }
        runtimeTool.projectStage(taskId, TaskEventTypeEnum.CONTEXT_COLLECTING, "Collecting task context");
        ContextAcquisitionResult result = facade.acquire(plan, workspaceContext, rawInstruction);
        if (result != null && result.isSuccess()) {
            PlanTaskSession session = sessionService.get(taskId);
            if (session != null) {
                memoryService.appendAssistantTurn(session, "已收集上下文：" + firstNonBlank(result.getContextSummary(), result.getMessage()));
                sessionService.saveWithoutVersionChange(session);
            }
            runtimeTool.projectStage(taskId, TaskEventTypeEnum.CONTEXT_COLLECTED, firstNonBlank(result.getContextSummary(), "Context collected"));
        }
        return result == null ? ContextAcquisitionResult.failure("没有读取到可用上下文。") : result;
    }

    public WorkspaceContext mergeWorkspaceContext(WorkspaceContext original, ContextAcquisitionResult result) {
        WorkspaceContext merged = copy(original);
        if (result == null) {
            return merged;
        }
        List<String> selectedMessages = new ArrayList<>(merged.getSelectedMessages() == null
                ? List.of()
                : merged.getSelectedMessages());
        if (result.getSelectedMessages() != null) {
            selectedMessages.addAll(result.getSelectedMessages());
        }
        if (result.getDocFragments() != null) {
            selectedMessages.addAll(result.getDocFragments());
        }
        merged.setSelectedMessages(selectedMessages.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());

        List<String> selectedMessageIds = new ArrayList<>(merged.getSelectedMessageIds() == null
                ? List.of()
                : merged.getSelectedMessageIds());
        if (result.getSelectedMessageIds() != null) {
            selectedMessageIds.addAll(result.getSelectedMessageIds());
        }
        merged.setSelectedMessageIds(selectedMessageIds.stream()
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList());
        return merged;
    }

    private WorkspaceContext copy(WorkspaceContext original) {
        if (original == null) {
            return WorkspaceContext.builder().build();
        }
        return WorkspaceContext.builder()
                .selectionType(original.getSelectionType())
                .timeRange(original.getTimeRange())
                .selectedMessages(original.getSelectedMessages())
                .selectedMessageIds(original.getSelectedMessageIds())
                .attachmentRefs(original.getAttachmentRefs())
                .docRefs(original.getDocRefs())
                .chatId(original.getChatId())
                .threadId(original.getThreadId())
                .messageId(original.getMessageId())
                .senderOpenId(original.getSenderOpenId())
                .chatType(original.getChatType())
                .inputSource(original.getInputSource())
                .continuationMode(original.getContinuationMode())
                .profession(original.getProfession())
                .industry(original.getIndustry())
                .audience(original.getAudience())
                .tone(original.getTone())
                .language(original.getLanguage())
                .promptProfile(original.getPromptProfile())
                .promptVersion(original.getPromptVersion())
                .build();
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
