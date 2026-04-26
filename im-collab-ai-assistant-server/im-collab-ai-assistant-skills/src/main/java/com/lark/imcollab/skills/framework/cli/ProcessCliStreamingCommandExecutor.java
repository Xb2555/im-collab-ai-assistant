package com.lark.imcollab.skills.framework.cli;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ProcessCliStreamingCommandExecutor implements CliStreamingCommandExecutor {

    @Override
    public CliStreamHandle start(CliCommand command, CliStreamListener listener) throws IOException {
        if (command.stdin() != null) {
            throw new IllegalArgumentException("streaming cli command does not support stdin");
        }

        List<String> shellCommand = new ArrayList<>();
        shellCommand.add(command.executable());
        shellCommand.addAll(command.arguments());

        ProcessBuilder processBuilder = new ProcessBuilder(shellCommand);
        processBuilder.redirectErrorStream(true);
        if (command.workingDirectory() != null && !command.workingDirectory().isBlank()) {
            processBuilder.directory(new File(command.workingDirectory()));
        }

        Process process = processBuilder.start();
        ProcessStreamHandle handle = new ProcessStreamHandle(process);
        Thread readerThread = new Thread(() -> readLines(process, listener, handle),
                "lark-cli-stream-" + System.identityHashCode(process));
        readerThread.setDaemon(true);
        readerThread.start();
        return handle;
    }

    private void readLines(Process process, CliStreamListener listener, ProcessStreamHandle handle) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                listener.onLine(line);
            }
            int exitCode = process.waitFor();
            handle.markStopped();
            listener.onExit(exitCode);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            handle.markStopped();
            listener.onError(exception);
        } catch (Exception exception) {
            handle.markStopped();
            listener.onError(exception);
        }
    }

    private static final class ProcessStreamHandle implements CliStreamHandle {

        private final Process process;
        private final AtomicBoolean running = new AtomicBoolean(true);

        private ProcessStreamHandle(Process process) {
            this.process = process;
        }

        @Override
        public boolean isRunning() {
            return running.get() && process.isAlive();
        }

        @Override
        public void stop() {
            if (!running.getAndSet(false)) {
                return;
            }
            process.destroy();
            try {
                if (!process.waitFor(3, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }

        private void markStopped() {
            running.set(false);
        }
    }
}
