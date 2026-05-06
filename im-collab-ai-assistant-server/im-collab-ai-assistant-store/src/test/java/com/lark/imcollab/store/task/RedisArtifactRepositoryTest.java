package com.lark.imcollab.store.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lark.imcollab.common.domain.Artifact;
import com.lark.imcollab.common.domain.ArtifactType;
import com.lark.imcollab.store.redis.RedisJsonStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RedisArtifactRepositoryTest {

    @Test
    void summaryArtifactDoesNotOverrideOwnedDocumentIndex() {
        RedisArtifactRepository repository = new RedisArtifactRepository(new InMemoryRedisJsonStore(), objectMapper());
        Artifact owned = Artifact.builder()
                .artifactId("doc-link-1")
                .taskId("task-1")
                .type(ArtifactType.DOC_LINK)
                .documentId("doc-1")
                .externalUrl("https://example/doc-1")
                .ownerScenario("SCENARIO_C_DOCUMENT_GENERATION")
                .createdBySystem(true)
                .createdAt(Instant.now())
                .build();
        Artifact summary = Artifact.builder()
                .artifactId("summary-1")
                .taskId("doc-iter-1")
                .type(ArtifactType.SUMMARY)
                .documentId("doc-1")
                .externalUrl("https://example/doc-1")
                .ownerScenario("SCENARIO_C_DOCUMENT_ITERATION")
                .createdBySystem(true)
                .createdAt(Instant.now())
                .build();

        repository.save(owned);
        repository.save(summary);

        assertThat(repository.findOwnedDocumentRecordByDocumentId("doc-1")).contains(owned);
        assertThat(repository.findOwnedDocumentRecordByExternalUrl("https://example/doc-1")).contains(owned);
        assertThat(repository.findByDocumentId("doc-1")).contains(owned);
    }

    @Test
    void summaryOnlyIsNotEditableOwnedRecord() {
        RedisArtifactRepository repository = new RedisArtifactRepository(new InMemoryRedisJsonStore(), objectMapper());
        Artifact summary = Artifact.builder()
                .artifactId("summary-1")
                .taskId("doc-iter-1")
                .type(ArtifactType.SUMMARY)
                .documentId("doc-1")
                .externalUrl("https://example/doc-1")
                .ownerScenario("SCENARIO_C_DOCUMENT_ITERATION")
                .createdBySystem(true)
                .createdAt(Instant.now())
                .build();

        repository.save(summary);

        assertThat(repository.findOwnedDocumentRecordByDocumentId("doc-1")).isEmpty();
        assertThat(repository.findOwnedDocumentRecordByExternalUrl("https://example/doc-1")).isEmpty();
    }

    @Test
    void deleteArtifactRemovesStoredRecordAndIndexes() {
        RedisArtifactRepository repository = new RedisArtifactRepository(new InMemoryRedisJsonStore(), objectMapper());
        Artifact owned = Artifact.builder()
                .artifactId("doc-link-1")
                .taskId("task-1")
                .type(ArtifactType.DOC_LINK)
                .documentId("doc-1")
                .externalUrl("https://example/doc-1")
                .ownerScenario("SCENARIO_C_DOCUMENT_GENERATION")
                .createdBySystem(true)
                .createdAt(Instant.now())
                .build();
        repository.save(owned);

        repository.deleteArtifact("task-1", "doc-link-1");

        assertThat(repository.findByTaskId("task-1")).isEmpty();
        assertThat(repository.findByDocumentId("doc-1")).isEmpty();
        assertThat(repository.findByExternalUrl("https://example/doc-1")).isEmpty();
    }

    private static class InMemoryRedisJsonStore implements RedisJsonStore {
        private final Map<String, Object> data = new HashMap<>();

        @Override
        public void set(String key, Object value, Duration ttl) {
            data.put(key, value);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.ofNullable((T) data.get(key));
        }

        @Override
        public void delete(String key) {
            data.remove(key);
        }
    }

    private ObjectMapper objectMapper() {
        return JsonMapper.builder().findAndAddModules().build();
    }
}
