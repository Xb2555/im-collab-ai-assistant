package com.lark.imcollab.common.port;

import com.lark.imcollab.common.domain.Artifact;

import java.util.List;

public interface ArtifactRepository {
    void save(Artifact artifact);
    List<Artifact> findByTaskId(String taskId);
}
