package com.lark.imcollab.harness.document.support;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;

import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DocumentExecutionGuardTest {

    @Test
    void distributedLockWaitsForPreviousAttemptToDrain() throws Exception {
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redissonClient);
        when(redissonClient.getLock("harness:document:task-1")).thenReturn(lock);
        when(lock.tryLock(5, 600, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        new DocumentExecutionGuard(provider).execute("task-1", () -> {
        });

        verify(lock).tryLock(5, 600, TimeUnit.SECONDS);
        verify(lock).unlock();
    }
}
