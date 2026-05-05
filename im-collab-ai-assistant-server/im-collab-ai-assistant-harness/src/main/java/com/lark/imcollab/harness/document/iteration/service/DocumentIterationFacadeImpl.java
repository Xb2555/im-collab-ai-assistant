package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.facade.DocumentIterationFacade;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;
import org.springframework.stereotype.Service;

@Service
public class DocumentIterationFacadeImpl implements DocumentIterationFacade {

    private final DocumentIterationExecutionService documentIterationExecutionService;

    public DocumentIterationFacadeImpl(DocumentIterationExecutionService documentIterationExecutionService) {
        this.documentIterationExecutionService = documentIterationExecutionService;
    }

    @Override
    public DocumentIterationVO edit(DocumentIterationRequest request) {
        return documentIterationExecutionService.execute(request);
    }

    @Override
    public DocumentIterationVO decide(String taskId, DocumentIterationApprovalRequest request, String operatorOpenId) {
        return documentIterationExecutionService.decide(taskId, request, operatorOpenId);
    }
}
