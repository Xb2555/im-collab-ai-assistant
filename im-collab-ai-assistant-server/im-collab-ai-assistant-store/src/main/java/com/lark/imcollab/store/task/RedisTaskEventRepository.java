package com.lark.imcollab.store.task;

import com.lark.imcollab.common.domain.TaskEvent;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Repository
public class RedisTaskEventRepository implements TaskEventRepository {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "events:task:";

    private final RedisJsonStore store;
    private final ObjectMapper objectMapper;

    public RedisTaskEventRepository(RedisJsonStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(TaskEvent event) {
        List<TaskEvent> events = findByTaskId(event.getTaskId());
        events.add(event);
        store.set(PREFIX + event.getTaskId(), events, TTL);
    }

    @Override
    public List<TaskEvent> findByTaskId(String taskId) {
        return store.get(PREFIX + taskId, List.class)
                .map(raw -> objectMapper.convertValue(raw, new TypeReference<List<TaskEvent>>() {}))
                .orElse(new ArrayList<>());
    }
}
