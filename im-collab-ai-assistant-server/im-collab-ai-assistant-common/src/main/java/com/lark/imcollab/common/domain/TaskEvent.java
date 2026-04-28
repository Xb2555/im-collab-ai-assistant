package com.lark.imcollab.common.domain;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
public class TaskEvent implements Serializable {
    private String eventId;
    private String taskId;
    private String stepId;
    private TaskEventType type;
    private String payload;
    private Instant occurredAt;
}
