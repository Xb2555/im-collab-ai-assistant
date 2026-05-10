package com.lark.imcollab.common.service;

public final class ExecutionAttemptContext {

    private static final ThreadLocal<Attempt> CURRENT = new ThreadLocal<>();

    private ExecutionAttemptContext() {
    }

    public static Scope open(String taskId, String executionAttemptId) {
        Attempt previous = CURRENT.get();
        CURRENT.set(new Attempt(taskId, executionAttemptId));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static String currentTaskId() {
        Attempt attempt = CURRENT.get();
        return attempt == null ? null : attempt.taskId();
    }

    public static String currentExecutionAttemptId() {
        Attempt attempt = CURRENT.get();
        return attempt == null ? null : attempt.executionAttemptId();
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record Attempt(String taskId, String executionAttemptId) {
    }
}
