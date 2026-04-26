package com.lark.imcollab.skills.framework.cli;

public interface CliStreamListener {

    void onLine(String line);

    default void onError(Exception exception) {
    }

    default void onExit(int exitCode) {
    }
}
