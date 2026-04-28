package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.Task;

import java.util.Optional;

public interface TaskRepository {
    void save(Task task);
    Optional<Task> findById(String taskId);
    void updateStatus(String taskId, com.lark.imcollab.common.domain.TaskStatus status);
}
