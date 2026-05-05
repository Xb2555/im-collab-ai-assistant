package com.lark.imcollab.harness.document.iteration.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.exception.AiAssistantException;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.TaskRepository;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentOwnershipGuard {

    private static final Pattern DOC_URL_PATTERN = Pattern.compile("/(?:docx|wiki)/([A-Za-z0-9]+)");

    private final ArtifactRepository artifactRepository;
    private final TaskRepository taskRepository;
    private final PlannerStateStore plannerStateStore;

    public DocumentOwnershipGuard(
            ArtifactRepository artifactRepository,
            TaskRepository taskRepository,
            PlannerStateStore plannerStateStore
    ) {
        this.artifactRepository = artifactRepository;
        this.taskRepository = taskRepository;
        this.plannerStateStore = plannerStateStore;
    }

    public Artifact assertEditable(String docRef, String operatorOpenId, String requestedTaskId) {
        if (docRef == null || docRef.isBlank()) {
            throw new AiAssistantException(BusinessCode.PARAMS_ERROR, "docUrl must be provided");
        }
        if (operatorOpenId == null || operatorOpenId.isBlank()) {
            throw new AiAssistantException(BusinessCode.NOT_LOGIN_ERROR, "operatorOpenId must be provided");
        }
        String docId = extractDocumentId(docRef);
        Artifact artifact = resolveOwnedArtifact(docRef, docId, requestedTaskId)
                .orElseThrow(() -> new AiAssistantException(
                        BusinessCode.FORBIDDEN_ERROR,
                        "只允许迭代本系统生成并登记过的飞书文档"
                ));
        validateOwnedDocumentRecord(artifact);
        if (requestedTaskId != null && !requestedTaskId.isBlank() && !requestedTaskId.equals(artifact.getTaskId())) {
            throw new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "请求 taskId 与文档归属任务不一致");
        }
        String ownerOpenId = resolveTaskOwner(artifact.getTaskId());
        if (ownerOpenId != null && !ownerOpenId.isBlank() && !ownerOpenId.equals(operatorOpenId)) {
            throw new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "当前用户无权编辑该系统文档");
        }
        return artifact;
    }

    private Optional<Artifact> resolveOwnedArtifact(String docRef, String docId, String requestedTaskId) {
        if (requestedTaskId != null && !requestedTaskId.isBlank()) {
            Optional<Artifact> byTask = artifactRepository.findLatestDocArtifactByTaskId(requestedTaskId)
                    .filter(artifact -> matchesDoc(artifact, docRef, docId));
            if (byTask.isPresent()) {
                return byTask;
            }
        }
        return artifactRepository.findOwnedDocumentRecordByDocumentId(docId)
                .or(() -> artifactRepository.findOwnedDocumentRecordByExternalUrl(docRef.trim()));
    }

    private void validateOwnedDocumentRecord(Artifact artifact) {
        if (!artifact.isCreatedBySystem()) {
            throw new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "目标文档未被系统登记为可编辑文档");
        }
        if (artifact.getType() != ArtifactType.DOC_LINK) {
            throw new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "目标文档归属记录不是可编辑主记录");
        }
        if (!"SCENARIO_C_DOCUMENT_GENERATION".equals(artifact.getOwnerScenario())) {
            throw new AiAssistantException(BusinessCode.FORBIDDEN_ERROR, "目标文档不在允许的迭代场景范围内");
        }
    }

    private boolean matchesDoc(Artifact artifact, String docRef, String docId) {
        return artifact != null && (
                (artifact.getDocumentId() != null && artifact.getDocumentId().equals(docId))
                        || (artifact.getExternalUrl() != null && artifact.getExternalUrl().equals(docRef.trim()))
        );
    }

    private String resolveTaskOwner(String taskId) {
        return taskRepository.findById(taskId)
                .map(task -> task.getUserId())
                .filter(value -> value != null && !value.isBlank())
                .or(() -> plannerStateStore.findTask(taskId).map(task -> task.getOwnerOpenId()))
                .orElse(null);
    }

    private String extractDocumentId(String docIdOrUrl) {
        if (docIdOrUrl == null || docIdOrUrl.isBlank()) {
            return docIdOrUrl;
        }
        String trimmed = docIdOrUrl.trim();
        Matcher matcher = DOC_URL_PATTERN.matcher(trimmed);
        return matcher.find() ? matcher.group(1) : trimmed;
    }
}
