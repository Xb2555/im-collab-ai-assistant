package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.Artifact;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository {
    void save(Artifact artifact);
    List<Artifact> findByTaskId(String taskId);
    Optional<Artifact> findByExternalUrl(String externalUrl);
    Optional<Artifact> findByDocumentId(String documentId);
    Optional<Artifact> findOwnedDocumentRecordByExternalUrl(String externalUrl);
    Optional<Artifact> findOwnedDocumentRecordByDocumentId(String documentId);
    Optional<Artifact> findLatestDocArtifactByTaskId(String taskId);

    default void deleteArtifact(String taskId, String artifactId) {
    }

    default void deleteByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        findByTaskId(taskId).stream()
                .filter(artifact -> artifact != null && artifact.getArtifactId() != null && !artifact.getArtifactId().isBlank())
                .forEach(artifact -> deleteArtifact(taskId, artifact.getArtifactId()));
    }
}
