package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.Approval;

import java.util.Optional;

public interface ApprovalRepository {
    void save(Approval approval);
    Optional<Approval> findByStepId(String stepId);
    void updateStatus(String approvalId, com.lark.imcollab.common.domain.ApprovalStatus status, String feedback);
}
