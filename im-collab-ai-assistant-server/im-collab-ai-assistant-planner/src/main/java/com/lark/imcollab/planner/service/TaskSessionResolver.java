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

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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

        Optional<PlanTaskSession> pendingSelectionSession = Optional.ofNullable(
                stateStore.findPendingSelectionSession(continuationKey)
        ).orElse(Optional.empty());
        if (pendingSelectionSession.isPresent()) {
            return new TaskSessionResolution(pendingSelectionSession.get().getTaskId(), true, continuationKey);
        }

        Optional<ConversationTaskState> conversationState = stateStore.findConversationTaskState(continuationKey);
        Optional<String> boundTaskId = stateStore.findConversationTaskId(continuationKey);
        Optional<PlanTaskSession> boundSession = boundTaskId
                .filter(this::hasText)
                .flatMap(stateStore::findSession);
        if (boundSession.filter(this::isPendingSelectionSession).isPresent()) {
            return new TaskSessionResolution(boundSession.get().getTaskId(), true, continuationKey);
        }
        Optional<String> stateTaskId = conversationState
                .flatMap(this::preferredTaskId)
                .filter(this::hasText);
        if (stateTaskId.isPresent()) {
            Optional<PlanTaskSession> existingSession = stateStore.findSession(stateTaskId.get());
            if (existingSession.isPresent()) {
                return new TaskSessionResolution(stateTaskId.get(), true, continuationKey);
            }
        }
        if (boundTaskId.isEmpty()) {
            return new TaskSessionResolution(UUID.randomUUID().toString(), false, continuationKey);
        }

        if (boundSession.isPresent()) {
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
        return !resolveEditableArtifacts(taskId).isEmpty();
    }

    public List<ArtifactRecord> resolveEditableArtifacts(String taskId) {
        if (!hasText(taskId)) {
            return List.of();
        }
        return stateStore.findArtifactsByTaskId(taskId).stream()
                .filter(artifact -> artifact != null
                        && hasText(artifact.getUrl())
                        && (artifact.getType() == ArtifactTypeEnum.PPT
                        || artifact.getType() == ArtifactTypeEnum.DOC))
                .toList();
    }

    public Optional<ArtifactTypeEnum> inferEditableArtifactType(String taskId, String userInput) {
        return inferEditableArtifact(taskId, userInput)
                .map(ArtifactRecord::getType);
    }

    public Optional<ArtifactRecord> inferEditableArtifact(String taskId, String userInput) {
        List<ArtifactRecord> editableArtifacts = resolveEditableArtifacts(taskId);
        if (editableArtifacts.isEmpty()) {
            return Optional.empty();
        }
        if (editableArtifacts.size() == 1) {
            return Optional.of(editableArtifacts.get(0));
        }
        String normalized = normalizeUserInput(userInput);
        ArtifactTypeHint hint = inferArtifactTypeHint(normalized);
        if (hint.type() == null) {
            return Optional.empty();
        }
        List<ArtifactRecord> matchedArtifacts = editableArtifacts.stream()
                .filter(artifact -> artifact.getType() == hint.type())
                .sorted(editableArtifactComparator().reversed())
                .toList();
        if (matchedArtifacts.isEmpty()) {
            return Optional.empty();
        }
        if (hint.explicit()) {
            return Optional.of(matchedArtifacts.get(0));
        }
        return matchedArtifacts.size() == 1 ? Optional.of(matchedArtifacts.get(0)) : Optional.empty();
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
        List<ArtifactRecord> artifacts = stateStore.findArtifactsByTaskId(task.getTaskId());
        List<ArtifactTypeEnum> artifactTypes = artifacts.stream()
                .map(ArtifactRecord::getType)
                .filter(type -> type != null)
                .distinct()
                .toList();
        Instant fallbackCreatedAt = firstNonNull(
                task.getCreatedAt(),
                artifacts.stream()
                        .map(ArtifactRecord::getCreatedAt)
                        .filter(value -> value != null)
                        .min(Comparator.naturalOrder())
                        .orElse(null),
                task.getUpdatedAt(),
                artifacts.stream()
                        .map(ArtifactRecord::getUpdatedAt)
                        .filter(value -> value != null)
                        .max(Comparator.naturalOrder())
                        .orElse(null)
        );
        Instant fallbackUpdatedAt = firstNonNull(
                task.getUpdatedAt(),
                artifacts.stream()
                        .map(ArtifactRecord::getUpdatedAt)
                        .filter(value -> value != null)
                        .max(Comparator.naturalOrder())
                        .orElse(null),
                fallbackCreatedAt
        );
        return PendingTaskCandidate.builder()
                .taskId(task.getTaskId())
                .title(task.getTitle())
                .goal(task.getGoal())
                .artifactTypes(artifactTypes)
                .createdAt(fallbackCreatedAt)
                .updatedAt(fallbackUpdatedAt)
                .build();
    }

    @SafeVarargs
    private final <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
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

    private boolean isPendingSelectionSession(PlanTaskSession session) {
        if (session == null || session.getIntakeState() == null) {
            return false;
        }
        return session.getIntakeState().getPendingTaskSelection() != null
                || session.getIntakeState().getPendingArtifactSelection() != null;
    }

    private String normalizeUserInput(String userInput) {
        return userInput == null ? "" : userInput.toLowerCase(Locale.ROOT);
    }

    private ArtifactTypeHint inferArtifactTypeHint(String normalizedInput) {
        if (!hasText(normalizedInput)) {
            return ArtifactTypeHint.none();
        }
        boolean mentionsDoc = mentionsDocumentArtifact(normalizedInput);
        boolean mentionsPpt = mentionsPresentationArtifact(normalizedInput);
        if (mentionsDoc && !mentionsPpt) {
            return ArtifactTypeHint.explicit(ArtifactTypeEnum.DOC);
        }
        if (mentionsPpt && !mentionsDoc) {
            return ArtifactTypeHint.explicit(ArtifactTypeEnum.PPT);
        }
        boolean looksLikeDoc = looksLikeDocumentArtifactEdit(normalizedInput);
        boolean looksLikePpt = looksLikePresentationArtifactEdit(normalizedInput);
        if (looksLikeDoc && !looksLikePpt) {
            return ArtifactTypeHint.semantic(ArtifactTypeEnum.DOC);
        }
        if (looksLikePpt && !looksLikeDoc) {
            return ArtifactTypeHint.semantic(ArtifactTypeEnum.PPT);
        }
        return ArtifactTypeHint.none();
    }

    private boolean mentionsDocumentArtifact(String normalizedInput) {
        return normalizedInput.contains("文档")
                || normalizedInput.contains("doc")
                || normalizedInput.contains("docx");
    }

    private boolean mentionsPresentationArtifact(String normalizedInput) {
        return normalizedInput.contains("ppt")
                || normalizedInput.contains("slides")
                || normalizedInput.contains("演示稿")
                || normalizedInput.contains("幻灯片")
                || normalizedInput.contains("幻灯");
    }

    private boolean looksLikeDocumentArtifactEdit(String normalizedInput) {
        boolean sectionCue = normalizedInput.contains("小节")
                || normalizedInput.contains("章节")
                || normalizedInput.contains("章节")
                || normalizedInput.contains("段落")
                || normalizedInput.contains("正文")
                || normalizedInput.contains("目录")
                || normalizedInput.contains("文末")
                || normalizedInput.contains("末尾补一节")
                || normalizedInput.contains("项目总结");
        boolean pageCue = normalizedInput.contains("第1页")
                || normalizedInput.contains("第一页")
                || normalizedInput.contains("第2页")
                || normalizedInput.contains("第二页")
                || normalizedInput.contains("最后一页")
                || normalizedInput.contains("末尾补一页");
        return sectionCue && !pageCue;
    }

    private boolean looksLikePresentationArtifactEdit(String normalizedInput) {
        return normalizedInput.contains("页")
                || normalizedInput.contains("封面")
                || normalizedInput.contains("版式")
                || normalizedInput.contains("一页")
                || normalizedInput.contains("第二页")
                || normalizedInput.contains("第一页")
                || normalizedInput.contains("最后一页");
    }

    private Comparator<ArtifactRecord> editableArtifactComparator() {
        return Comparator
                .comparing(ArtifactRecord::getUpdatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ArtifactRecord::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ArtifactRecord::getArtifactId, Comparator.nullsLast(String::compareTo));
    }

    private record ArtifactTypeHint(ArtifactTypeEnum type, boolean explicit) {

        private static ArtifactTypeHint explicit(ArtifactTypeEnum type) {
            return new ArtifactTypeHint(type, true);
        }

        private static ArtifactTypeHint semantic(ArtifactTypeEnum type) {
            return new ArtifactTypeHint(type, false);
        }

        private static ArtifactTypeHint none() {
            return new ArtifactTypeHint(null, false);
        }
    }
}
