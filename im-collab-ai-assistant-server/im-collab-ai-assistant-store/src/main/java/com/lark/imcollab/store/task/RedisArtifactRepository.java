package com.lark.imcollab.store.task;

import com.lark.imcollab.common.domain.Artifact;
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

    private static final Duration TTL = Duration.ofHours(24);
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
        if (hasText(artifact.getDocumentId())) {
            store.set(DOC_ID_PREFIX + artifact.getDocumentId().trim(), artifact, TTL);
        }
        if (hasText(artifact.getExternalUrl())) {
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
    public Optional<Artifact> findLatestDocArtifactByTaskId(String taskId) {
        return findByTaskId(taskId).stream()
                .filter(Objects::nonNull)
                .filter(artifact -> artifact.getType() == com.lark.imcollab.common.domain.ArtifactType.DOC_LINK)
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
