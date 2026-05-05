package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationRequest;
import com.lark.imcollab.common.model.vo.DocumentIterationVO;

public interface DocumentIterationFacade {

    DocumentIterationVO edit(DocumentIterationRequest request);

    DocumentIterationVO decide(String taskId, DocumentIterationApprovalRequest request, String operatorOpenId);
}
