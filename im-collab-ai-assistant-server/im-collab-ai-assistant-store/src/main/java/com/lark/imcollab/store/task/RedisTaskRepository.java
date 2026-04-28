package com.lark.imcollab.store.task;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskStatus;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.store.redis.RedisJsonStore;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Repository
public class RedisTaskRepository implements TaskRepository {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "task:";

    private final RedisJsonStore store;

    public RedisTaskRepository(RedisJsonStore store) {
        this.store = store;
    }

    @Override
    public void save(Task task) {
        store.set(PREFIX + task.getTaskId(), task, TTL);
    }

    @Override
    public Optional<Task> findById(String taskId) {
        return store.get(PREFIX + taskId, Task.class);
    }

    @Override
    public void updateStatus(String taskId, TaskStatus status) {
        findById(taskId).ifPresent(task -> {
            task.setStatus(status);
            task.setUpdatedAt(Instant.now());
            save(task);
        });
    }
}
