package com.lark.imcollab.store.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.ConversationTaskState;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
@RequiredArgsConstructor
public class RedisPlannerStateStore implements PlannerStateStore {

    private static final String SESSION_KEY_PREFIX = "planner:session:";
    private static final String EVENT_KEY_PREFIX = "planner:events:";
    private static final String TASK_KEY_PREFIX = "planner:runtime:task:";
    private static final String STEP_KEY_PREFIX = "planner:runtime:step:";
    private static final String TASK_STEP_KEY_PREFIX = "planner:runtime:task-steps:";
    private static final String ARTIFACT_KEY_PREFIX = "planner:runtime:artifact:";
    private static final String TASK_ARTIFACT_KEY_PREFIX = "planner:runtime:task-artifacts:";
    private static final String RUNTIME_EVENT_KEY_PREFIX = "planner:runtime:events:";
    private static final String SUBMISSION_KEY_PREFIX = "planner:submission:";
    private static final String EVALUATION_KEY_PREFIX = "planner:evaluation:";
    private static final String LATEST_EVALUATION_KEY_PREFIX = "planner:evaluation-latest:";
    private static final String CONVERSATION_KEY_PREFIX = "planner:conversation:";
    private static final String CONVERSATION_STATE_KEY_PREFIX = "planner:conversation-state:";
    private static final String USER_TASK_KEY_PREFIX = "planner:user-tasks:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);
    private static final DefaultRedisScript<Long> SAVE_SESSION_IF_REVISION_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('GET', KEYS[1])
            local expected_revision = tonumber(ARGV[2])
            if not current then
                if expected_revision == 0 then
                    redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
                    return 1
                end
                return 0
            end
            local decoded = cjson.decode(current)
            local current_revision = tonumber(decoded['stateRevision'] or 0)
            if current_revision ~= expected_revision then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void saveSession(PlanTaskSession session) {
        try {
            String key = SESSION_KEY_PREFIX + session.getTaskId();
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session: " + session.getTaskId(), e);
        }
    }

    @Override
    public boolean saveSessionIfStateRevision(PlanTaskSession session, long expectedStateRevision) {
        try {
            String key = SESSION_KEY_PREFIX + session.getTaskId();
            String json = objectMapper.writeValueAsString(session);
            Long result = redisTemplate.execute(
                    SAVE_SESSION_IF_REVISION_SCRIPT,
                    List.of(key),
                    json,
                    String.valueOf(expectedStateRevision),
                    String.valueOf(SESSION_TTL.toMillis())
            );
            return Long.valueOf(1L).equals(result);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session with state revision check: " + session.getTaskId(), e);
        }
    }

    @Override
    public Optional<PlanTaskSession> findSession(String taskId) {
        try {
            String key = SESSION_KEY_PREFIX + taskId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, PlanTaskSession.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load session: " + taskId, e);
        }
    }

    @Override
    public Optional<String> findConversationTaskId(String conversationKey) {
        String value = redisTemplate.opsForValue().get(CONVERSATION_KEY_PREFIX + conversationKey);
        return Optional.ofNullable(value);
    }

    @Override
    public void saveConversationTaskBinding(String conversationKey, String taskId) {
        redisTemplate.opsForValue().set(CONVERSATION_KEY_PREFIX + conversationKey, taskId, SESSION_TTL);
    }

    @Override
    public Optional<ConversationTaskState> findConversationTaskState(String conversationKey) {
        try {
            String json = redisTemplate.opsForValue().get(CONVERSATION_STATE_KEY_PREFIX + conversationKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, ConversationTaskState.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load conversation task state: " + conversationKey, e);
        }
    }

    @Override
    public void saveConversationTaskState(ConversationTaskState state) {
        try {
            if (state == null || state.getConversationKey() == null || state.getConversationKey().isBlank()) {
                return;
            }
            redisTemplate.opsForValue().set(
                    CONVERSATION_STATE_KEY_PREFIX + state.getConversationKey(),
                    objectMapper.writeValueAsString(state),
                    SESSION_TTL
            );
            if (hasText(state.getActiveTaskId())) {
                saveConversationTaskBinding(state.getConversationKey(), state.getActiveTaskId());
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save conversation task state: "
                    + (state == null ? null : state.getConversationKey()), e);
        }
    }

    @Override
    public void clearConversationExecutingTask(String conversationKey, String taskId) {
        findConversationTaskState(conversationKey).ifPresent(state -> {
            if (!sameText(taskId, state.getExecutingTaskId())) {
                return;
            }
            state.setExecutingTaskId(null);
            state.setUpdatedAt(Instant.now());
            saveConversationTaskState(state);
        });
    }

    @Override
    public Optional<PlanTaskSession> findPendingSelectionSession(String conversationKey) {
        if (!hasText(conversationKey)) {
            return Optional.empty();
        }
        Set<String> sessionKeys = redisTemplate.keys(SESSION_KEY_PREFIX + "*");
        if (sessionKeys == null || sessionKeys.isEmpty()) {
            return Optional.empty();
        }
        return sessionKeys.stream()
                .map(key -> redisTemplate.opsForValue().get(key))
                .filter(this::hasText)
                .map(this::readSessionSafely)
                .flatMap(Optional::stream)
                .filter(session -> session.getIntakeState() != null)
                .filter(session -> sameText(conversationKey, session.getIntakeState().getContinuationKey()))
                .filter(session -> session.getIntakeState().getPendingTaskSelection() != null
                        || session.getIntakeState().getPendingArtifactSelection() != null)
                .max(Comparator.comparing(PlanTaskSession::getStateRevision)
                        .thenComparing(PlanTaskSession::getVersion));
    }

    @Override
    public void appendEvent(TaskEvent event) {
        try {
            String key = EVENT_KEY_PREFIX + event.getTaskId();
            String json = objectMapper.writeValueAsString(event);
            redisTemplate.opsForList().rightPush(key, json);
            redisTemplate.expire(key, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to append event for task: " + event.getTaskId(), e);
        }
    }

    @Override
    public List<String> getEventJsonList(String taskId) {
        String key = EVENT_KEY_PREFIX + taskId;
        List<String> result = redisTemplate.opsForList().range(key, 0, -1);
        return result == null ? List.of() : result;
    }

    @Override
    public void saveTask(TaskRecord task) {
        try {
            String key = TASK_KEY_PREFIX + task.getTaskId();
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(task), SESSION_TTL);
            if (hasText(task.getOwnerOpenId())) {
                String userTasksKey = USER_TASK_KEY_PREFIX + task.getOwnerOpenId();
                redisTemplate.opsForZSet().add(userTasksKey, task.getTaskId(), taskSortScore(task));
                redisTemplate.expire(userTasksKey, SESSION_TTL);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save runtime task: " + task.getTaskId(), e);
        }
    }

    @Override
    public Optional<TaskRecord> findTask(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(TASK_KEY_PREFIX + taskId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load runtime task: " + taskId, e);
        }
    }

    @Override
    public List<TaskRecord> findTasksByOwner(String ownerOpenId, List<TaskStatusEnum> statuses, int offset, int limit) {
        if (!hasText(ownerOpenId)) {
            return List.of();
        }
        try {
            Set<String> taskIds = redisTemplate.opsForZSet().reverseRange(USER_TASK_KEY_PREFIX + ownerOpenId, 0, -1);
            if (taskIds == null || taskIds.isEmpty()) {
                return List.of();
            }
            Set<TaskStatusEnum> statusFilter = statuses == null || statuses.isEmpty()
                    ? Set.of()
                    : new HashSet<>(statuses);
            return taskIds.stream()
                    .map(this::findTask)
                    .flatMap(Optional::stream)
                    .filter(task -> ownerOpenId.equals(task.getOwnerOpenId()))
                    .filter(task -> statusFilter.isEmpty() || statusFilter.contains(task.getStatus()))
                    .skip(Math.max(0, offset))
                    .limit(Math.max(1, limit))
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load user tasks: " + ownerOpenId, e);
        }
    }

    @Override
    public List<TaskRecord> findTasksByConversation(
            String inputSource,
            String chatId,
            String threadId,
            String ownerOpenId,
            List<TaskStatusEnum> statuses,
            int limit
    ) {
        if (!hasText(chatId) || !hasText(ownerOpenId)) {
            return List.of();
        }
        int fetchLimit = Math.max(20, Math.max(1, limit) * 4);
        return findTasksByOwner(ownerOpenId, statuses, 0, fetchLimit).stream()
                .filter(task -> sameText(chatId, task.getChatId()))
                .filter(task -> !hasText(inputSource) || sameText(inputSource, task.getSource()))
                .filter(task -> sameThread(threadId, task.getThreadId()))
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public void saveStep(TaskStepRecord step) {
        try {
            String stepKey = stepKey(step.getTaskId(), step.getStepId());
            String taskStepsKey = TASK_STEP_KEY_PREFIX + step.getTaskId();
            redisTemplate.opsForValue().set(stepKey, objectMapper.writeValueAsString(step), SESSION_TTL);
            redisTemplate.opsForSet().add(taskStepsKey, step.getStepId());
            redisTemplate.expire(taskStepsKey, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save runtime step: " + step.getStepId(), e);
        }
    }

    @Override
    public List<TaskStepRecord> findStepsByTaskId(String taskId) {
        try {
            java.util.Set<String> stepIds = redisTemplate.opsForSet().members(TASK_STEP_KEY_PREFIX + taskId);
            if (stepIds == null || stepIds.isEmpty()) {
                return List.of();
            }
            return stepIds.stream()
                    .map(stepId -> findStep(taskId, stepId))
                    .flatMap(Optional::stream)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load runtime steps for task: " + taskId, e);
        }
    }

    @Override
    public Optional<TaskStepRecord> findStep(String stepId) {
        try {
            String json = redisTemplate.opsForValue().get(STEP_KEY_PREFIX + stepId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskStepRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load runtime step: " + stepId, e);
        }
    }

    @Override
    public void saveArtifact(ArtifactRecord artifact) {
        try {
            String artifactKey = ARTIFACT_KEY_PREFIX + artifact.getArtifactId();
            String taskArtifactsKey = TASK_ARTIFACT_KEY_PREFIX + artifact.getTaskId();
            redisTemplate.opsForValue().set(artifactKey, objectMapper.writeValueAsString(artifact), SESSION_TTL);
            redisTemplate.opsForSet().add(taskArtifactsKey, artifact.getArtifactId());
            redisTemplate.expire(taskArtifactsKey, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save runtime artifact: " + artifact.getArtifactId(), e);
        }
    }

    @Override
    public List<ArtifactRecord> findArtifactsByTaskId(String taskId) {
        try {
            java.util.Set<String> artifactIds = redisTemplate.opsForSet().members(TASK_ARTIFACT_KEY_PREFIX + taskId);
            if (artifactIds == null || artifactIds.isEmpty()) {
                return List.of();
            }
            return artifactIds.stream()
                    .map(artifactId -> redisTemplate.opsForValue().get(ARTIFACT_KEY_PREFIX + artifactId))
                    .filter(java.util.Objects::nonNull)
                    .map(this::readArtifact)
                    .toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load runtime artifacts for task: " + taskId, e);
        }
    }

    @Override
    public void deleteArtifact(String taskId, String artifactId) {
        if (!hasText(taskId) || !hasText(artifactId)) {
            return;
        }
        redisTemplate.delete(ARTIFACT_KEY_PREFIX + artifactId.trim());
        redisTemplate.opsForSet().remove(TASK_ARTIFACT_KEY_PREFIX + taskId.trim(), artifactId.trim());
        redisTemplate.expire(TASK_ARTIFACT_KEY_PREFIX + taskId.trim(), SESSION_TTL);
    }

    @Override
    public void appendRuntimeEvent(TaskEventRecord event) {
        try {
            String key = RUNTIME_EVENT_KEY_PREFIX + event.getTaskId();
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(event));
            redisTemplate.expire(key, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to append runtime event for task: " + event.getTaskId(), e);
        }
    }

    @Override
    public List<TaskEventRecord> findRuntimeEventsByTaskId(String taskId) {
        try {
            List<String> events = redisTemplate.opsForList().range(RUNTIME_EVENT_KEY_PREFIX + taskId, 0, -1);
            if (events == null || events.isEmpty()) {
                return List.of();
            }
            return events.stream().map(this::readRuntimeEvent).toList();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load runtime events for task: " + taskId, e);
        }
    }

    @Override
    public void saveSubmission(TaskSubmissionResult submission) {
        try {
            String key = SUBMISSION_KEY_PREFIX + submission.getTaskId() + ":" + submission.getAgentTaskId();
            String json = objectMapper.writeValueAsString(submission);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save submission: " + submission.getAgentTaskId(), e);
        }
    }

    @Override
    public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
        try {
            String key = SUBMISSION_KEY_PREFIX + taskId + ":" + agentTaskId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskSubmissionResult.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load submission: " + agentTaskId, e);
        }
    }

    @Override
    public void saveEvaluation(TaskResultEvaluation evaluation) {
        try {
            String key = EVALUATION_KEY_PREFIX + evaluation.getTaskId() + ":" + evaluation.getAgentTaskId();
            String latestKey = LATEST_EVALUATION_KEY_PREFIX + evaluation.getTaskId();
            String json = objectMapper.writeValueAsString(evaluation);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
            redisTemplate.opsForValue().set(latestKey, json, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save evaluation: " + evaluation.getAgentTaskId(), e);
        }
    }

    @Override
    public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) {
        try {
            String key = EVALUATION_KEY_PREFIX + taskId + ":" + agentTaskId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskResultEvaluation.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load evaluation: " + agentTaskId, e);
        }
    }

    @Override
    public Optional<TaskResultEvaluation> findLatestEvaluation(String taskId) {
        try {
            String json = redisTemplate.opsForValue().get(LATEST_EVALUATION_KEY_PREFIX + taskId);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, TaskResultEvaluation.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load latest evaluation: " + taskId, e);
        }
    }

    private ArtifactRecord readArtifact(String json) {
        try {
            return objectMapper.readValue(json, ArtifactRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse runtime artifact", e);
        }
    }

    private Optional<TaskStepRecord> findStep(String taskId, String stepId) {
        try {
            String json = redisTemplate.opsForValue().get(stepKey(taskId, stepId));
            if (json == null) {
                json = redisTemplate.opsForValue().get(STEP_KEY_PREFIX + stepId);
            }
            if (json == null) {
                return Optional.empty();
            }
            TaskStepRecord step = objectMapper.readValue(json, TaskStepRecord.class);
            return taskId.equals(step.getTaskId()) ? Optional.of(step) : Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to load runtime step: " + taskId + "/" + stepId, e);
        }
    }

    private String stepKey(String taskId, String stepId) {
        return STEP_KEY_PREFIX + taskId + ":" + stepId;
    }

    private TaskEventRecord readRuntimeEvent(String json) {
        try {
            return objectMapper.readValue(json, TaskEventRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse runtime event", e);
        }
    }

    private double taskSortScore(TaskRecord task) {
        Instant timestamp = task.getUpdatedAt() != null ? task.getUpdatedAt() : task.getCreatedAt();
        return timestamp == null ? System.currentTimeMillis() : timestamp.toEpochMilli();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Optional<PlanTaskSession> readSessionSafely(String json) {
        try {
            return Optional.of(objectMapper.readValue(json, PlanTaskSession.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean sameText(String expected, String actual) {
        return hasText(expected) && hasText(actual) && expected.trim().equals(actual.trim());
    }

    private boolean sameThread(String expected, String actual) {
        String left = hasText(expected) ? expected.trim() : "chat-root";
        String right = hasText(actual) ? actual.trim() : "chat-root";
        return left.equals(right);
    }
}
