package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.Artifact;

import java.util.List;
import java.util.Optional;

public interface ArtifactRepository {
    void save(Artifact artifact);
    List<Artifact> findByTaskId(String taskId);
    Optional<Artifact> findByExternalUrl(String externalUrl);
    Optional<Artifact> findByDocumentId(String documentId);
    Optional<Artifact> findLatestDocArtifactByTaskId(String taskId);
}
