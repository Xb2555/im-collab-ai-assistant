package com.lark.imcollab.planner.service;

public record TaskSessionResolution(
        String taskId,
        boolean existingSession,
        String continuationKey
) {
}
