package com.lark.imcollab.harness.presentation.support;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

@Component
public class PresentationExecutionGuard {

    private final ObjectProvider<RedissonClient> redissonClientProvider;
    private final Map<String, ReentrantLock> localLocks = new ConcurrentHashMap<>();

    public PresentationExecutionGuard(ObjectProvider<RedissonClient> redissonClientProvider) {
        this.redissonClientProvider = redissonClientProvider;
    }

    public void execute(String lockKey, Runnable action) {
        RedissonClient redissonClient = redissonClientProvider.getIfAvailable();
        if (redissonClient != null) {
            executeWithDistributedLock(redissonClient.getLock("harness:presentation:" + lockKey), action);
            return;
        }
        ReentrantLock lock = localLocks.computeIfAbsent(lockKey, ignored -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    private void executeWithDistributedLock(RLock lock, Runnable action) {
        boolean acquired = false;
        try {
            acquired = lock.tryLock(1, 30, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("Task is already executing");
            }
            action.run();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for execution lock", exception);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
