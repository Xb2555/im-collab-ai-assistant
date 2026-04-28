package com.lark.imcollab.store.task;

import com.lark.imcollab.common.domain.Step;
import com.lark.imcollab.common.domain.StepStatus;
import com.lark.imcollab.common.port.StepRepository;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class RedisStepRepository implements StepRepository {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String STEP_PREFIX = "step:";
    private static final String LIST_PREFIX = "steps:task:";

    private final RedisJsonStore store;
    private final ObjectMapper objectMapper;

    public RedisStepRepository(RedisJsonStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Step step) {
        store.set(STEP_PREFIX + step.getStepId(), step, TTL);
        List<Step> steps = findByTaskId(step.getTaskId());
        steps.removeIf(s -> s.getStepId().equals(step.getStepId()));
        steps.add(step);
        store.set(LIST_PREFIX + step.getTaskId(), steps, TTL);
    }

    @Override
    public List<Step> findByTaskId(String taskId) {
        return store.get(LIST_PREFIX + taskId, List.class)
                .map(raw -> objectMapper.convertValue(raw, new TypeReference<List<Step>>() ))
                .orElse(new ArrayList<>());
    }

    @Override
    public void updateStatus(String stepId, StepStatus status) {
        store.get(STEP_PREFIX + stepId, Step.class).ifPresent(step -> {
            step.setStatus(status);
            step.setUpdatedAt(Instant.now());
            save(step);
        });
    }
}
