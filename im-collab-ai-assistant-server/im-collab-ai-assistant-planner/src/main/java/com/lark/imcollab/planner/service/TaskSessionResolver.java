package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TaskSessionResolver {

    private final PlannerStateStore stateStore;

    public TaskSessionResolution resolve(String explicitTaskId, WorkspaceContext workspaceContext) {
        if (hasText(explicitTaskId)) {
            String taskId = explicitTaskId.trim();
            boolean existing = stateStore.findSession(taskId).isPresent();
            return new TaskSessionResolution(taskId, existing, buildConversationKey(workspaceContext));
        }

        String continuationKey = buildConversationKey(workspaceContext);
        if (!hasText(continuationKey)) {
            return new TaskSessionResolution(UUID.randomUUID().toString(), false, null);
        }

        Optional<String> boundTaskId = stateStore.findConversationTaskId(continuationKey);
        if (boundTaskId.isEmpty()) {
            return new TaskSessionResolution(UUID.randomUUID().toString(), false, continuationKey);
        }

        Optional<PlanTaskSession> existingSession = stateStore.findSession(boundTaskId.get());
        if (existingSession.isPresent()) {
            return new TaskSessionResolution(boundTaskId.get(), true, continuationKey);
        }
        return new TaskSessionResolution(UUID.randomUUID().toString(), false, continuationKey);
    }

    public void bindConversation(TaskSessionResolution resolution) {
        if (resolution == null || !hasText(resolution.continuationKey())) {
            return;
        }
        stateStore.saveConversationTaskBinding(resolution.continuationKey(), resolution.taskId());
    }

    private String buildConversationKey(WorkspaceContext workspaceContext) {
        if (workspaceContext == null || !hasText(workspaceContext.getChatId())) {
            return null;
        }
        String source = hasText(workspaceContext.getInputSource()) ? workspaceContext.getInputSource().trim() : "UNKNOWN";
        String chatId = workspaceContext.getChatId().trim();
        String threadId = hasText(workspaceContext.getThreadId()) ? workspaceContext.getThreadId().trim() : "chat-root";
        return source + ":" + chatId + ":" + threadId;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
