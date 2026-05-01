package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.skills.lark.doc.LarkDocTool;
import org.springframework.stereotype.Component;

@Component
public class DocumentOwnershipGuard {

    private final ArtifactRepository artifactRepository;
    private final LarkDocTool larkDocTool;

    public DocumentOwnershipGuard(ArtifactRepository artifactRepository, LarkDocTool larkDocTool) {
        this.artifactRepository = artifactRepository;
        this.larkDocTool = larkDocTool;
    }

    public Artifact assertEditable(String docRef) {
        if (docRef == null || docRef.isBlank()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "docUrl must be provided");
        }
        String docId = larkDocTool.extractDocumentId(docRef);
        Artifact artifact = artifactRepository.findByDocumentId(docId)
                .or(() -> artifactRepository.findByExternalUrl(docRef.trim()))
                .orElseThrow(() -> new AiAssistantException(
                        BusinessCode.FORBIDDEN_ERROR,
                        "只允许迭代本系统生成并登记过的飞书文档"
                ));
        if (!artifact.isCreatedBySystem()) {
            throw new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "目标文档未被系统登记为可编辑文档");
        }
        return artifact;
    }
}
