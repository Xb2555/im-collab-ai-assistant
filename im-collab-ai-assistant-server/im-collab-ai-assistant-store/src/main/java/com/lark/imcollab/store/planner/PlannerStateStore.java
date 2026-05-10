package com.lark.imcollab.store.planner;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;

import java.util.List;
import java.util.Optional;

public interface PlannerStateStore {

    void saveSession(PlanTaskSession session);

    default boolean saveSessionIfStateRevision(PlanTaskSession session, long expectedStateRevision) {
        saveSession(session);
        return true;
    }

    Optional<PlanTaskSession> findSession(String taskId);

    Optional<String> findConversationTaskId(String conversationKey);

    void saveConversationTaskBinding(String conversationKey, String taskId);

    default Optional<ConversationTaskState> findConversationTaskState(String conversationKey) {
        return Optional.empty();
    }

    default void saveConversationTaskState(ConversationTaskState state) {
    }

    default void clearConversationExecutingTask(String conversationKey, String taskId) {
    }

    default Optional<PlanTaskSession> findPendingSelectionSession(String conversationKey) {
        return Optional.empty();
    }

    void appendEvent(TaskEvent event);

    List<String> getEventJsonList(String taskId);

    void saveTask(TaskRecord task);

    Optional<TaskRecord> findTask(String taskId);

    List<TaskRecord> findTasksByOwner(String ownerOpenId, List<TaskStatusEnum> statuses, int offset, int limit);

    default List<TaskRecord> findTasksByConversation(
            String inputSource,
            String chatId,
            String threadId,
            String ownerOpenId,
            List<TaskStatusEnum> statuses,
            int limit
    ) {
        return List.of();
    }

    void saveStep(TaskStepRecord step);

    List<TaskStepRecord> findStepsByTaskId(String taskId);

    Optional<TaskStepRecord> findStep(String stepId);

    void saveArtifact(ArtifactRecord artifact);

    List<ArtifactRecord> findArtifactsByTaskId(String taskId);

    default void deleteArtifact(String taskId, String artifactId) {
    }

    void appendRuntimeEvent(TaskEventRecord event);

    List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId);

    void saveSubmission(TaskSubmissionResult submission);

    Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId);

    void saveEvaluation(TaskResultEvaluation evaluation);

    Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId);

    default Optional<TaskResultEvaluation> findLatestEvaluation(String taskId) {
        return Optional.empty();
    }
}
