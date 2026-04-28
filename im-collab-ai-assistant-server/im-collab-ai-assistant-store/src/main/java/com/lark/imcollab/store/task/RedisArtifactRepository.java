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

@Repository
public class RedisArtifactRepository implements ArtifactRepository {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String PREFIX = "artifacts:task:";

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
    }

    @Override
    public List<Artifact> findByTaskId(String taskId) {
        return store.get(PREFIX + taskId, List.class)
                .map(raw -> objectMapper.convertValue(raw, new TypeReference<List<Artifact>>() {}))
                .orElse(new ArrayList<>());
    }
}
