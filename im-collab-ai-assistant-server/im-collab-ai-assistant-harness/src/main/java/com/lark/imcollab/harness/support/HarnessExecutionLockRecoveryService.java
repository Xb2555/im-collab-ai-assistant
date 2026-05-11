package com.lark.imcollab.harness.support;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

@Service
public class HarnessExecutionLockRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(HarnessExecutionLockRecoveryService.class);

    private final ObjectProvider<RedissonClient> redissonClientProvider;

    public HarnessExecutionLockRecoveryService(ObjectProvider<RedissonClient> redissonClientProvider) {
        this.redissonClientProvider = redissonClientProvider;
    }

    public int clearStaleTaskLocks(String taskId) {
        if (!hasText(taskId)) {
            return 0;
        }
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient == null) {
            return 0;
        }
        int released = 0;
        released += forceUnlock(redissonClient.getLock("harness:document:" + taskId), "document", taskId);
        released += forceUnlock(redissonClient.getLock("harness:presentation:" + taskId), "presentation", taskId);
        return released;
    }

    private int forceUnlock(RLock lock, String lockType, String taskId) {
        try {
            if (lock == null || !lock.isLocked()) {
                return 0;
            }
            boolean unlocked = lock.forceUnlock();
            if (unlocked) {
                log.info("Force released stale harness {} lock before re-execution: taskId={}", lockType, taskId);
                return 1;
            }
            return 0;
        } catch (RuntimeException exception) {
            log.warn("Failed to force release stale harness {} lock: taskId={}", lockType, taskId, exception);
            return 0;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
