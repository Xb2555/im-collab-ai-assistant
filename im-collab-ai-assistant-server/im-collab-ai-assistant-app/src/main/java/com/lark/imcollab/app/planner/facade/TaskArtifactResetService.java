package com.lark.imcollab.app.planner.facade;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.store.planner.PlannerStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TaskArtifactResetService {

    private static final Logger log = LoggerFactory.getLogger(TaskArtifactResetService.class);

    private static final Pattern FILE_URL_PATTERN = Pattern.compile("/(docx|doc|sheets|slides)/([A-Za-z0-9]+)");

    private final ArtifactRepository artifactRepository;
    private final PlannerStateStore plannerStateStore;
    private final LarkCliClient larkCliClient;

    public TaskArtifactResetService(
            ArtifactRepository artifactRepository,
            PlannerStateStore plannerStateStore,
            LarkCliClient larkCliClient
    ) {
        this.artifactRepository = artifactRepository;
        this.plannerStateStore = plannerStateStore;
        this.larkCliClient = larkCliClient;
    }

    public void clearGeneratedArtifactsBeforeExecution(String taskId) {
        if (!hasText(taskId)) {
            return;
        }
        List<Artifact> domainArtifacts = artifactRepository.findByTaskId(taskId);
        List<ArtifactRecord> plannerArtifacts = plannerStateStore.findArtifactsByTaskId(taskId);
        if ((domainArtifacts == null || domainArtifacts.isEmpty())
                && (plannerArtifacts == null || plannerArtifacts.isEmpty())) {
            return;
        }
        log.info("Clearing generated artifacts before execution: taskId={}, domainArtifacts={}, plannerArtifacts={}",
                taskId,
                domainArtifacts == null ? 0 : domainArtifacts.size(),
                plannerArtifacts == null ? 0 : plannerArtifacts.size());

        for (Artifact artifact : domainArtifacts) {
            if (artifact == null || !artifact.isCreatedBySystem()) {
                continue;
            }
            deleteRemoteArtifact(artifact);
        }

        Set<String> artifactIds = new LinkedHashSet<>();
        domainArtifacts.stream()
                .filter(artifact -> artifact != null && hasText(artifact.getArtifactId()))
                .map(Artifact::getArtifactId)
                .forEach(artifactIds::add);
        plannerArtifacts.stream()
                .filter(artifact -> artifact != null && hasText(artifact.getArtifactId()))
                .map(ArtifactRecord::getArtifactId)
                .forEach(artifactIds::add);

        for (String artifactId : artifactIds) {
            log.info("Removing local artifact records before execution: taskId={}, artifactId={}", taskId, artifactId);
            artifactRepository.deleteArtifact(taskId, artifactId);
            plannerStateStore.deleteArtifact(taskId, artifactId);
        }

        plannerStateStore.findTask(taskId).ifPresent(task -> {
            task.setArtifactIds(List.of());
            task.setUpdatedAt(Instant.now());
            plannerStateStore.saveTask(task);
        });
        log.info("Generated artifacts cleared before execution: taskId={}, removedArtifacts={}", taskId, artifactIds.size());
    }

    private void deleteRemoteArtifact(Artifact artifact) {
        RemoteArtifactTarget target = resolveTarget(artifact);
        if (target == null) {
            return;
        }
        RuntimeException firstFailure = null;
        RuntimeException secondFailure = null;
        List<String> identities = preferredIdentities(artifact.getType());
        for (int index = 0; index < identities.size(); index++) {
            try {
                executeDriveDelete(target, identities.get(index));
                log.info("Deleted remote artifact before execution: type={}, token={}, identity={}",
                        target.type(), target.fileToken(), identities.get(index));
                return;
            } catch (RuntimeException exception) {
                if (index == 0) {
                    firstFailure = exception;
                } else {
                    secondFailure = exception;
                }
            }
        }
        String message = "删除旧产物失败: type=" + target.type()
                + ", token=" + target.fileToken()
                + ", " + identities.get(0) + "Error=" + compact(firstFailure)
                + ", " + identities.get(1) + "Error=" + compact(secondFailure);
        throw new IllegalStateException(message, secondFailure == null ? firstFailure : secondFailure);
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
        log.info("Deleting remote artifact before execution via drive+delete: type={}, token={}, identity={}",
                target.type(), target.fileToken(), identity);
        CliCommandResult result = larkCliClient.execute(args);
        if (!result.isSuccess()) {
            throw new IllegalStateException(larkCliClient.extractErrorMessage(result.output()));
        }
    }

    private RemoteArtifactTarget resolveTarget(Artifact artifact) {
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

    private String extractTokenFromUrl(String url) {
        if (!hasText(url)) {
            return null;
        }
        Matcher matcher = FILE_URL_PATTERN.matcher(url.trim());
        return matcher.find() ? matcher.group(2) : null;
    }

    private String compact(RuntimeException exception) {
        if (exception == null || exception.getMessage() == null) {
            return "unknown";
        }
        String message = exception.getMessage().trim();
        return message.length() <= 160 ? message : message.substring(0, 160);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record RemoteArtifactTarget(String type, String fileToken) {
    }
}
