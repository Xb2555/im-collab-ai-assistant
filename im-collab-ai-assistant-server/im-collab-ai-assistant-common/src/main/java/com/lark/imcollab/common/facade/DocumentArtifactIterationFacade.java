package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.dto.DocumentArtifactIterationRequest;
import com.lark.imcollab.common.model.dto.DocumentIterationApprovalRequest;
import com.lark.imcollab.common.model.vo.DocumentArtifactIterationResult;

public interface DocumentArtifactIterationFacade {

    DocumentArtifactIterationResult edit(DocumentArtifactIterationRequest request);

    DocumentArtifactIterationResult decide(
            String iterationTaskId,
            String artifactId,
            String docUrl,
            DocumentIterationApprovalRequest request,
            String operatorOpenId
    );
}
