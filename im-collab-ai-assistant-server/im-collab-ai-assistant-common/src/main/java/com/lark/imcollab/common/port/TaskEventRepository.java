package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.TaskEvent;

import java.util.List;

public interface TaskEventRepository {
    void save(TaskEvent event);
    List<TaskEvent> findByTaskId(String taskId);
}
