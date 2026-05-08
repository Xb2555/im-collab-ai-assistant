package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.TaskEventTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskEventRecord implements Serializable {

    private String eventId;
    private String taskId;
    private String stepId;
    private String artifactId;
    private TaskEventTypeEnum type;
    private String payloadJson;
    private int version;
    private int planVersion;
    private String executionAttemptId;
    @Builder.Default
    private Instant createdAt = Instant.now();
}
