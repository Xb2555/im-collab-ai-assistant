package com.lark.imcollab.planner.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskEvent;
import com.lark.imcollab.common.model.entity.TaskResultEvaluation;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PlannerStateRepository {

    private static final String SESSION_KEY_PREFIX = "planner:session:";
    private static final String EVENT_KEY_PREFIX = "planner:events:";
    private static final String SUBMISSION_KEY_PREFIX = "planner:submission:";
    private static final String EVALUATION_KEY_PREFIX = "planner:evaluation:";
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void saveSession(PlanTaskSession session) {
        try {
            String key = SESSION_KEY_PREFIX + session.getTaskId();
            String json = objectMapper.writeValueAsString(session);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session: " + session.getTaskId(), e);
        }
    }

    public Optional<PlanTaskSession> findSession(String taskId) {
        try {
            String key = SESSION_KEY_PREFIX + taskId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, PlanTaskSession.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load session: " + taskId, e);
        }
    }

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

    public List<String> getEventJsonList(String taskId) {
        String key = EVENT_KEY_PREFIX + taskId;
        List<String> result = redisTemplate.opsForList().range(key, 0, -1);
        return result == null ? List.of() : result;
    }

    public void saveSubmission(TaskSubmissionResult submission) {
        try {
            String key = SUBMISSION_KEY_PREFIX + submission.getTaskId() + ":" + submission.getAgentTaskId();
            String json = objectMapper.writeValueAsString(submission);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save submission: " + submission.getAgentTaskId(), e);
        }
    }

    public Optional<TaskSubmissionResult> findSubmission(String taskId, String agentTaskId) {
        try {
            String key = SUBMISSION_KEY_PREFIX + taskId + ":" + agentTaskId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TaskSubmissionResult.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load submission: " + agentTaskId, e);
        }
    }

    public void saveEvaluation(TaskResultEvaluation evaluation) {
        try {
            String key = EVALUATION_KEY_PREFIX + evaluation.getTaskId() + ":" + evaluation.getAgentTaskId();
            String json = objectMapper.writeValueAsString(evaluation);
            redisTemplate.opsForValue().set(key, json, SESSION_TTL);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save evaluation: " + evaluation.getAgentTaskId(), e);
        }
    }

    public Optional<TaskResultEvaluation> findEvaluation(String taskId, String agentTaskId) {
        try {
            String key = EVALUATION_KEY_PREFIX + taskId + ":" + agentTaskId;
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, TaskResultEvaluation.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to load evaluation: " + agentTaskId, e);
        }
    }
}
