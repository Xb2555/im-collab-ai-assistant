package com.lark.imcollab.common.service;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskCancellationRegistry {

    private final Set<String> cancelledTaskIds = ConcurrentHashMap.newKeySet();

    public void markCancelled(String taskId) {
        if (hasText(taskId)) {
            cancelledTaskIds.add(taskId);
        }
    }

    public void clear(String taskId) {
        if (hasText(taskId)) {
            cancelledTaskIds.remove(taskId);
        }
    }

    public boolean isCancelled(String taskId) {
        return hasText(taskId) && cancelledTaskIds.contains(taskId);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
