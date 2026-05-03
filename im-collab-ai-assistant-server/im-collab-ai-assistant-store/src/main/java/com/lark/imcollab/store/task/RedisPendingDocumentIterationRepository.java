package com.lark.imcollab.store.task;

import com.lark.imcollab.common.model.entity.PendingDocumentIteration;
import com.lark.imcollab.common.port.PendingDocumentIterationRepository;
import com.lark.imcollab.store.redis.RedisJsonStore;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
public class RedisPendingDocumentIterationRepository implements PendingDocumentIterationRepository {

    private static final Duration TTL = Duration.ofDays(3650);
    private static final String PREFIX = "document-iteration:pending:";

    private final RedisJsonStore store;

    public RedisPendingDocumentIterationRepository(RedisJsonStore store) {
        this.store = store;
    }

    @Override
    public void save(PendingDocumentIteration pending) {
        store.set(PREFIX + pending.getTaskId(), pending, TTL);
    }

    @Override
    public Optional<PendingDocumentIteration> findByTaskId(String taskId) {
        return store.get(PREFIX + taskId, PendingDocumentIteration.class);
    }

    @Override
    public void deleteByTaskId(String taskId) {
        store.delete(PREFIX + taskId);
    }
}
