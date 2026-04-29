package com.lark.imcollab.harness.document.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.common.domain.*;
import com.lark.imcollab.common.port.ArtifactRepository;
import com.lark.imcollab.common.port.TaskEventRepository;
import com.lark.imcollab.common.port.TaskRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class DocumentExecutionSupport {

    public static final String OUTLINE_TASK_SUFFIX = "generate_outline";
    public static final String SECTIONS_TASK_SUFFIX = "generate_sections";
    public static final String REVIEW_TASK_SUFFIX = "review_doc";
    public static final String WRITE_TASK_SUFFIX = "write_doc_and_sync";

    private final TaskRepository taskRepository;
    private final TaskEventRepository eventRepository;
    private final ArtifactRepository artifactRepository;
    private final ObjectMapper objectMapper;

    public DocumentExecutionSupport(
            TaskRepository taskRepository,
            TaskEventRepository eventRepository,
            ArtifactRepository artifactRepository,
            ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.eventRepository = eventRepository;
        this.artifactRepository = artifactRepository;
        this.objectMapper = objectMapper;
    }

    public Task loadTask(String taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    public void saveArtifact(String taskId, String stepId, ArtifactType type, String title, String content, String url) {
        artifactRepository.save(Artifact.builder()
                .artifactId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(type)
                .title(title)
                .content(content)
                .externalUrl(url)
                .createdAt(Instant.now())
                .build());
    }

    public void publishEvent(String taskId, String stepId, TaskEventType type) {
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(type)
                .occurredAt(Instant.now())
                .build());
    }

    public void publishApprovalRequest(String taskId, String stepId, String prompt) {
        eventRepository.save(TaskEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .taskId(taskId)
                .stepId(stepId)
                .type(TaskEventType.APPROVAL_REQUESTED)
                .payload(prompt)
                .occurredAt(Instant.now())
                .build());
    }

    public <T> T execute(Supplier<T> executor) {
        return executor.get();
    }

    public String subtaskId(String taskId, String suffix) {
        return taskId + ":document:" + suffix;
    }

    public String sectionSubtaskId(String taskId, String sectionKey) {
        return taskId + ":document:section:" + sectionKey;
    }

    public String writeJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return String.valueOf(payload);
        }
    }

    public static class HumanReviewRequiredException extends RuntimeException {
        private final String prompt;

        public HumanReviewRequiredException(String prompt) {
            super("Human review required");
            this.prompt = prompt;
        }

        public String getPrompt() {
            return prompt;
        }
    }

    public static class RetryExhaustedException extends RuntimeException {
        private final String rawOutput;

        public RetryExhaustedException(String rawOutput) {
            super("Retry exhausted");
            this.rawOutput = rawOutput;
        }

        public String getRawOutput() {
            return rawOutput;
        }
    }
}
