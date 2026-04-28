package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Service
public class LarkOutboundMessageRetryService {

    private static final Logger log = LoggerFactory.getLogger(LarkOutboundMessageRetryService.class);
    private static final long INITIAL_DELAY_MILLIS = 5_000L;
    private static final long MAX_DELAY_MILLIS = 60_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final LarkMessageReplyTool replyTool;
    private final ScheduledExecutorService scheduler;
    private final Map<String, RetryTask> pendingTasks = new ConcurrentHashMap<>();

    @Autowired
    public LarkOutboundMessageRetryService(LarkMessageReplyTool replyTool) {
        this(replyTool, INITIAL_DELAY_MILLIS, MAX_DELAY_MILLIS, MAX_ATTEMPTS);
    }

    LarkOutboundMessageRetryService(
            LarkMessageReplyTool replyTool,
            long initialDelayMillis,
            long maxDelayMillis,
            int maxAttempts
    ) {
        this.replyTool = replyTool;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new RetryThreadFactory());
        this.initialDelayMillis = initialDelayMillis;
        this.maxDelayMillis = maxDelayMillis;
        this.maxAttempts = maxAttempts;
    }

    private final long initialDelayMillis;
    private final long maxDelayMillis;
    private final int maxAttempts;

    public void enqueuePrivateText(String openId, String text, String idempotencyKey) {
        enqueue(new RetryTask(
                RetryTaskType.PRIVATE_TEXT,
                "private:" + idempotencyKey,
                openId,
                text,
                idempotencyKey,
                0
        ));
    }

    public void enqueueReplyText(String messageId, String text, String idempotencyKey) {
        enqueue(new RetryTask(
                RetryTaskType.REPLY_TEXT,
                "reply:" + idempotencyKey,
                messageId,
                text,
                idempotencyKey,
                0
        ));
    }

    private void enqueue(RetryTask task) {
        RetryTask existing = pendingTasks.putIfAbsent(task.dedupKey(), task);
        if (existing != null) {
            log.info("Skip enqueueing duplicate outbound retry task: key={}, target={}", task.dedupKey(), task.targetId());
            return;
        }
        log.warn("Queued outbound Lark message retry: key={}, type={}, target={}",
                task.dedupKey(), task.type(), task.targetId());
        schedule(task, initialDelayMillis);
    }

    private void schedule(RetryTask task, long delayMillis) {
        scheduler.schedule(() -> attempt(task.dedupKey()), delayMillis, TimeUnit.MILLISECONDS);
    }

    private void attempt(String dedupKey) {
        RetryTask task = pendingTasks.get(dedupKey);
        if (task == null) {
            return;
        }

        try {
            if (task.type() == RetryTaskType.PRIVATE_TEXT) {
                replyTool.sendPrivateText(task.targetId(), task.text(), task.idempotencyKey());
            } else {
                replyTool.replyText(task.targetId(), task.text(), task.idempotencyKey());
            }
            pendingTasks.remove(dedupKey);
            log.info("Outbound Lark message retry succeeded: key={}, target={}", dedupKey, task.targetId());
        } catch (RuntimeException exception) {
            int nextAttempt = task.attempt() + 1;
            if (nextAttempt >= maxAttempts) {
                pendingTasks.remove(dedupKey);
                log.error("Outbound Lark message retry exhausted: key={}, target={}, attempts={}",
                        dedupKey, task.targetId(), nextAttempt, exception);
                return;
            }
            RetryTask nextTask = task.withAttempt(nextAttempt);
            pendingTasks.put(dedupKey, nextTask);
            long nextDelay = Math.min(initialDelayMillis * (1L << Math.max(0, nextAttempt - 1)), maxDelayMillis);
            log.warn("Outbound Lark message retry failed, rescheduling: key={}, target={}, nextAttempt={}, delayMs={}",
                    dedupKey, task.targetId(), nextAttempt + 1, nextDelay, exception);
            schedule(nextTask, nextDelay);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private enum RetryTaskType {
        PRIVATE_TEXT,
        REPLY_TEXT
    }

    private record RetryTask(
            RetryTaskType type,
            String dedupKey,
            String targetId,
            String text,
            String idempotencyKey,
            int attempt
    ) {
        private RetryTask withAttempt(int nextAttempt) {
            return new RetryTask(type, dedupKey, targetId, text, idempotencyKey, nextAttempt);
        }
    }

    private static final class RetryThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lark-outbound-retry");
            thread.setDaemon(true);
            return thread;
        }
    }
}
