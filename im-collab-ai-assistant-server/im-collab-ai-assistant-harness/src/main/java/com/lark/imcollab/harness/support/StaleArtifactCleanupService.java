package com.lark.imcollab.harness.support;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskEventRecord;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StaleArtifactCleanupService {

    private static final Logger log = LoggerFactory.getLogger(StaleArtifactCleanupService.class);
    private static final Pattern FILE_URL_PATTERN = Pattern.compile("/(docx|doc|sheets|slides)/([A-Za-z0-9]+)");

    private final LarkCliClient larkCliClient;
    private final PlannerStateStore stateStore;

    public StaleArtifactCleanupService(LarkCliClient larkCliClient, PlannerStateStore stateStore) {
        this.larkCliClient = larkCliClient;
        this.stateStore = stateStore;
    }

    @Async
    public void cleanup(Artifact artifact, String executionAttemptId, int planVersion) {
        RemoteArtifactTarget target = resolveTarget(artifact);
        if (target == null) {
            saveOrphanedRecord(artifact, executionAttemptId, planVersion, "SKIPPED");
            return;
        }
        saveOrphanedRecord(artifact, executionAttemptId, planVersion, "PENDING");
        List<String> identities = preferredIdentities(artifact.getType());
        RuntimeException lastFailure = null;
        for (String identity : identities) {
            try {
                executeDriveDelete(target, identity);
                saveOrphanedRecord(artifact, executionAttemptId, planVersion, "DELETED");
                appendCleanupEvent(artifact, executionAttemptId, planVersion,
                        "已删除迟到旧产物：" + firstNonBlank(artifact.getTitle(), artifact.getArtifactId()));
                return;
            } catch (RuntimeException exception) {
                lastFailure = exception;
                log.warn("Failed to delete stale artifact: artifactId={}, type={}, token={}, identity={}, error={}",
                        artifact.getArtifactId(), target.type(), target.fileToken(), identity, exception.getMessage());
            }
        }
        saveOrphanedRecord(artifact, executionAttemptId, planVersion, "FAILED");
        appendCleanupEvent(artifact, executionAttemptId, planVersion,
                "迟到旧产物删除失败：" + (lastFailure == null ? "unknown" : lastFailure.getMessage()));
    }

    private void executeDriveDelete(RemoteArtifactTarget target, String identity) {
        List<String> args = new ArrayList<>();
        args.add("drive");
        args.add("+delete");
        args.add("--as");
        args.add(identity);
        args.add("--file-token");
        args.add(target.fileToken());
        args.add("--type");
        args.add(target.type());
        args.add("--yes");
        CliCommandResult result = larkCliClient.execute(args);
        if (!result.isSuccess()) {
            throw new IllegalStateException(larkCliClient.extractErrorMessage(result.output()));
        }
    }

    private void saveOrphanedRecord(Artifact artifact, String executionAttemptId, int planVersion, String cleanupStatus) {
        if (artifact == null || !hasText(artifact.getArtifactId()) || !hasText(artifact.getTaskId())) {
            return;
        }
        stateStore.saveArtifact(ArtifactRecord.builder()
                .artifactId(artifact.getArtifactId())
                .taskId(artifact.getTaskId())
                .sourceStepId(artifact.getStepId())
                .type(mapType(artifact.getType()))
                .title(artifact.getTitle())
                .url(blankToNull(artifact.getExternalUrl()))
                .preview(blankToNull(artifact.getContent()))
                .status("STALE")
                .visibility("ORPHANED")
                .cleanupStatus(cleanupStatus)
                .planVersion(planVersion)
                .executionAttemptId(executionAttemptId)
                .version(1)
                .createdAt(artifact.getCreatedAt() == null ? Instant.now() : artifact.getCreatedAt())
                .updatedAt(Instant.now())
                .build());
    }

    private void appendCleanupEvent(Artifact artifact, String executionAttemptId, int planVersion, String message) {
        if (artifact == null || !hasText(artifact.getTaskId())) {
            return;
        }
        stateStore.appendRuntimeEvent(TaskEventRecord.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(artifact.getTaskId())
                .stepId(artifact.getStepId())
                .artifactId(artifact.getArtifactId())
                .type(TaskEventTypeEnum.STALE_ARTIFACT_DELETED)
                .payloadJson(toPayloadJson(message))
                .version(planVersion)
                .planVersion(planVersion)
                .executionAttemptId(executionAttemptId)
                .createdAt(Instant.now())
                .build());
    }

    private RemoteArtifactTarget resolveTarget(Artifact artifact) {
        if (artifact == null || artifact.getType() == null || !artifact.isCreatedBySystem()) {
            return null;
        }
        if (artifact.getType() == ArtifactType.DOC_LINK) {
            String token = firstNonBlank(artifact.getDocumentId(), extractTokenFromUrl(artifact.getExternalUrl()));
            return hasText(token) ? new RemoteArtifactTarget("docx", token.trim()) : null;
        }
        if (artifact.getType() == ArtifactType.SLIDES_LINK) {
            String token = firstNonBlank(artifact.getDocumentId(), extractTokenFromUrl(artifact.getExternalUrl()));
            return hasText(token) ? new RemoteArtifactTarget("slides", token.trim()) : null;
        }
        return null;
    }

    private List<String> preferredIdentities(ArtifactType type) {
        if (type == ArtifactType.DOC_LINK) {
            return List.of("bot", "user");
        }
        return List.of("user", "bot");
    }

    private ArtifactTypeEnum mapType(ArtifactType type) {
        if (type == ArtifactType.SLIDES_LINK) {
            return ArtifactTypeEnum.PPT;
        }
        if (type == ArtifactType.DOC_LINK) {
            return ArtifactTypeEnum.DOC;
        }
        return ArtifactTypeEnum.FILE;
    }

    private String extractTokenFromUrl(String url) {
        if (!hasText(url)) {
            return null;
        }
        Matcher matcher = FILE_URL_PATTERN.matcher(url.trim());
        return matcher.find() ? matcher.group(2) : null;
    }

    private String toPayloadJson(String payload) {
        if (!hasText(payload)) {
            return "null";
        }
        return "\"" + payload.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RemoteArtifactTarget(String type, String fileToken) {
    }
}
