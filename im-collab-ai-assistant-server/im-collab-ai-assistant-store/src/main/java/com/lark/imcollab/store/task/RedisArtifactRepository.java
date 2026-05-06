package com.lark.imcollab.store.task;

import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.store.redis.RedisJsonStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Repository
public class RedisArtifactRepository implements ArtifactRepository {

    private static final Duration TTL = Duration.ofDays(3650);
    private static final String PREFIX = "artifacts:task:";
    private static final String DOC_ID_PREFIX = "artifacts:doc:id:";
    private static final String DOC_URL_PREFIX = "artifacts:doc:url:";

    private final RedisJsonStore store;
    private final ObjectMapper objectMapper;

    public RedisArtifactRepository(RedisJsonStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(Artifact artifact) {
        List<Artifact> list = findByTaskId(artifact.getTaskId());
        list.removeIf(a -> a.getArtifactId().equals(artifact.getArtifactId()));
        list.add(artifact);
        store.set(PREFIX + artifact.getTaskId(), list, TTL);
        if (isOwnedDocumentRecord(artifact) && hasText(artifact.getDocumentId())) {
            store.set(DOC_ID_PREFIX + artifact.getDocumentId().trim(), artifact, TTL);
        }
        if (isOwnedDocumentRecord(artifact) && hasText(artifact.getExternalUrl())) {
            store.set(DOC_URL_PREFIX + artifact.getExternalUrl().trim(), artifact, TTL);
        }
    }

    @Override
    public List<Artifact> findByTaskId(String taskId) {
        return store.get(PREFIX + taskId, List.class)
                .map(raw -> objectMapper.convertValue(raw, new TypeReference<List<Artifact>>() {}))
                .orElse(new ArrayList<>());
    }

    @Override
    public Optional<Artifact> findByExternalUrl(String externalUrl) {
        if (!hasText(externalUrl)) {
            return Optional.empty();
        }
        return store.get(DOC_URL_PREFIX + externalUrl.trim(), Artifact.class);
    }

    @Override
    public Optional<Artifact> findByDocumentId(String documentId) {
        if (!hasText(documentId)) {
            return Optional.empty();
        }
        return store.get(DOC_ID_PREFIX + documentId.trim(), Artifact.class);
    }

    @Override
    public Optional<Artifact> findOwnedDocumentRecordByExternalUrl(String externalUrl) {
        return findByExternalUrl(externalUrl).filter(this::isOwnedDocumentRecord);
    }

    @Override
    public Optional<Artifact> findOwnedDocumentRecordByDocumentId(String documentId) {
        return findByDocumentId(documentId).filter(this::isOwnedDocumentRecord);
    }

    @Override
    public Optional<Artifact> findLatestDocArtifactByTaskId(String taskId) {
        return findByTaskId(taskId).stream()
                .filter(Objects::nonNull)
                .filter(this::isOwnedDocumentRecord)
                .filter(artifact -> hasText(artifact.getExternalUrl()) || hasText(artifact.getDocumentId()))
                .max((left, right) -> {
                    if (left.getCreatedAt() == null && right.getCreatedAt() == null) {
                        return 0;
                    }
                    if (left.getCreatedAt() == null) {
                        return -1;
                    }
                    if (right.getCreatedAt() == null) {
                        return 1;
                    }
                    return left.getCreatedAt().compareTo(right.getCreatedAt());
                });
    }

    @Override
    public void deleteArtifact(String taskId, String artifactId) {
        if (!hasText(taskId) || !hasText(artifactId)) {
            return;
        }
        List<Artifact> list = findByTaskId(taskId);
        Artifact removed = list.stream()
                .filter(artifact -> artifact != null && artifactId.trim().equals(artifact.getArtifactId()))
                .findFirst()
                .orElse(null);
        list.removeIf(artifact -> artifact != null && artifactId.trim().equals(artifact.getArtifactId()));
        if (list.isEmpty()) {
            store.delete(PREFIX + taskId.trim());
        } else {
            store.set(PREFIX + taskId.trim(), list, TTL);
        }
        if (removed != null) {
            if (hasText(removed.getDocumentId())) {
                store.delete(DOC_ID_PREFIX + removed.getDocumentId().trim());
            }
            if (hasText(removed.getExternalUrl())) {
                store.delete(DOC_URL_PREFIX + removed.getExternalUrl().trim());
            }
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isOwnedDocumentRecord(Artifact artifact) {
        return artifact != null
                && artifact.isCreatedBySystem()
                && artifact.getType() == ArtifactType.DOC_LINK
                && "SCENARIO_C_DOCUMENT_GENERATION".equals(artifact.getOwnerScenario());
    }
}
