package com.lark.imcollab.store.planner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
    private static final String CONVERSATION_KEY_PREFIX = "planner:conversation:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

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
    public void saveStep(TaskStepRecord step) {
        try {
            String stepKey = STEP_KEY_PREFIX + step.getStepId();
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
                    .map(this::findStep)
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
            String json = objectMapper.writeValueAsString(evaluation);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
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

    private ArtifactRecord readArtifact(String json) {
        try {
            return objectMapper.readValue(json, ArtifactRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse runtime artifact", e);
        }
    }

    private TaskEventRecord readRuntimeEvent(String json) {
        try {
            return objectMapper.readValue(json, TaskEventRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse runtime event", e);
        }
    }
}
