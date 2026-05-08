package com.lark.imcollab.harness.support;

public class ExecutionInterruptedException extends RuntimeException {

    public ExecutionInterruptedException(String message) {
        super(message);
    }

    public ExecutionInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
