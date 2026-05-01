package com.lark.imcollab.common.port;

import com.lark.imcollab.common.model.entity.PendingDocumentIteration;

import java.util.Optional;

public interface PendingDocumentIterationRepository {
    void save(PendingDocumentIteration pending);
    Optional<PendingDocumentIteration> findByTaskId(String taskId);
    void deleteByTaskId(String taskId);
}
