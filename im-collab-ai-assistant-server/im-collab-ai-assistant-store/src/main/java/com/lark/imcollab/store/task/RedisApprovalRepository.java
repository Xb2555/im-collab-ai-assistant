package com.lark.imcollab.store.task;

import com.lark.imcollab.common.domain.Approval;
import com.lark.imcollab.common.domain.ApprovalStatus;
import com.lark.imcollab.common.port.ApprovalRepository;
import com.lark.imcollab.store.redis.RedisJsonStore;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Repository
public class RedisApprovalRepository implements ApprovalRepository {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "approval:step:";

    private final RedisJsonStore store;

    public RedisApprovalRepository(RedisJsonStore store) {
        this.store = store;
    }

    @Override
    public void save(Approval approval) {
        store.set(PREFIX + approval.getStepId(), approval, TTL);
    }

    @Override
    public Optional<Approval> findByStepId(String stepId) {
        return store.get(PREFIX + stepId, Approval.class);
    }

    @Override
    public void updateStatus(String approvalId, ApprovalStatus status, String feedback) {
        // approvalId == stepId in this impl
        findByStepId(approvalId).ifPresent(a -> {
            a.setStatus(status);
            a.setUserFeedback(feedback);
            a.setDecidedAt(Instant.now());
            save(a);
        });
    }
}
