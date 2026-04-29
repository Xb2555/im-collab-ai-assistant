package com.lark.imcollab.gateway.im.event;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Component
public class LarkSdkMessageEventProcessor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LarkSdkMessageEventProcessor.class);

    private final ExecutorService executor;

    @Autowired
    public LarkSdkMessageEventProcessor() {
        this(Executors.newSingleThreadExecutor(new NamedDaemonThreadFactory()));
    }

    LarkSdkMessageEventProcessor(ExecutorService executor) {
        this.executor = executor;
    }

    public void submit(LarkMessageEvent event, Consumer<LarkMessageEvent> messageConsumer) {
        executor.execute(() -> process(event, messageConsumer));
    }

    private void process(LarkMessageEvent event, Consumer<LarkMessageEvent> messageConsumer) {
        try {
            messageConsumer.accept(event);
        } catch (RuntimeException exception) {
            log.warn("Failed to handle Lark SDK message receive event.", exception);
        }
    }

    @Override
    @PreDestroy
    public void close() {
        executor.shutdownNow();
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lark-sdk-message-event-processor-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
