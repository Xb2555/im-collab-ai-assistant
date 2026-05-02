package com.lark.imcollab.harness.document.iteration.service;

import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;

public interface DocumentIterationExecutionService {
    DocumentIterationVO execute(DocumentIterationRequest request);
    DocumentIterationVO decide(String taskId, DocumentIterationApprovalRequest request, String operatorOpenId);
}
