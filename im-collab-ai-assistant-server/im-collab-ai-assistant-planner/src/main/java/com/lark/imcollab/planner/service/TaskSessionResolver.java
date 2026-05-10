package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PendingTaskCandidate;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import com.lark.imcollab.store.planner.PlannerStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Comparator;

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

        Optional<ConversationTaskState> conversationState = stateStore.findConversationTaskState(continuationKey);
        Optional<String> stateTaskId = conversationState
                .flatMap(this::preferredTaskId)
                .filter(this::hasText);
        if (stateTaskId.isPresent()) {
            Optional<PlanTaskSession> existingSession = stateStore.findSession(stateTaskId.get());
            if (existingSession.isPresent()) {
                return new TaskSessionResolution(stateTaskId.get(), true, continuationKey);
            }
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

    public List<PendingTaskCandidate> resolveCompletedCandidates(WorkspaceContext workspaceContext) {
        if (workspaceContext == null || !hasText(workspaceContext.getChatId()) || !hasText(workspaceContext.getSenderOpenId())) {
            return List.of();
        }
        return stateStore.findTasksByConversation(
                        workspaceContext.getInputSource(),
                        workspaceContext.getChatId(),
                        workspaceContext.getThreadId(),
                        workspaceContext.getSenderOpenId(),
                        List.of(TaskStatusEnum.COMPLETED),
                        5
                ).stream()
                .map(this::toCandidate)
                .toList();
    }

    public boolean hasEditableArtifacts(String taskId) {
        if (!hasText(taskId)) {
            return false;
        }
        return stateStore.findArtifactsByTaskId(taskId).stream()
                .anyMatch(artifact -> artifact != null
                        && hasText(artifact.getUrl())
                        && (artifact.getType() == ArtifactTypeEnum.PPT
                                || artifact.getType() == ArtifactTypeEnum.DOC));
    }

    public boolean conversationHasEditableArtifacts(WorkspaceContext workspaceContext) {
        return resolveCompletedCandidates(workspaceContext).stream()
                .anyMatch(candidate -> candidate.getArtifactTypes() != null
                        && candidate.getArtifactTypes().stream()
                                .anyMatch(type -> type == ArtifactTypeEnum.PPT || type == ArtifactTypeEnum.DOC));
    }

    public Optional<ArtifactRecord> findLatestShareableArtifact(String taskId, ArtifactTypeEnum preferredType) {
        if (!hasText(taskId)) {
            return Optional.empty();
        }
        Comparator<ArtifactRecord> byNewest = Comparator
                .comparing(ArtifactRecord::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ArtifactRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()));
        List<ArtifactRecord> shareable = stateStore.findArtifactsByTaskId(taskId).stream()
                .filter(artifact -> artifact != null
                        && (artifact.getType() == ArtifactTypeEnum.DOC
                        || artifact.getType() == ArtifactTypeEnum.PPT
                        || artifact.getType() == ArtifactTypeEnum.SUMMARY)
                        && (hasText(artifact.getUrl())
                        || hasText(artifact.getPreview())
                        || hasText(artifact.getTitle())))
                .sorted(byNewest.reversed())
                .toList();
        if (shareable.isEmpty()) {
            return Optional.empty();
        }
        if (preferredType == null) {
            return Optional.of(shareable.get(0));
        }
        return shareable.stream()
                .filter(artifact -> artifact.getType() == preferredType)
                .findFirst()
                .or(() -> Optional.of(shareable.get(0)));
    }

    public Optional<ArtifactRecord> findArtifactById(String taskId, String artifactId) {
        if (!hasText(taskId) || !hasText(artifactId)) {
            return Optional.empty();
        }
        return stateStore.findArtifactsByTaskId(taskId).stream()
                .filter(artifact -> artifact != null && artifactId.trim().equals(artifact.getArtifactId()))
                .findFirst();
    }

    public String conversationKey(WorkspaceContext workspaceContext) {
        return buildConversationKey(workspaceContext);
    }

    public Optional<ConversationTaskState> conversationState(WorkspaceContext workspaceContext) {
        String key = buildConversationKey(workspaceContext);
        if (!hasText(key)) {
            return Optional.empty();
        }
        return stateStore.findConversationTaskState(key);
    }

    private PendingTaskCandidate toCandidate(TaskRecord task) {
        List<ArtifactTypeEnum> artifactTypes = stateStore.findArtifactsByTaskId(task.getTaskId()).stream()
                .map(ArtifactRecord::getType)
                .filter(type -> type != null)
                .distinct()
                .toList();
        return PendingTaskCandidate.builder()
                .taskId(task.getTaskId())
                .title(task.getTitle())
                .goal(task.getGoal())
                .artifactTypes(artifactTypes)
                .updatedAt(task.getUpdatedAt())
                .build();
    }

    private Optional<String> preferredTaskId(ConversationTaskState state) {
        if (state == null) {
            return Optional.empty();
        }
        if (hasText(state.getExecutingTaskId())) {
            return Optional.of(state.getExecutingTaskId().trim());
        }
        if (hasText(state.getActiveTaskId())) {
            return Optional.of(state.getActiveTaskId().trim());
        }
        return Optional.empty();
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
