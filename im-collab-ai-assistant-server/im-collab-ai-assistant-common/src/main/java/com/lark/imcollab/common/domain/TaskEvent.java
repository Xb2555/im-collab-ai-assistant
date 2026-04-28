package com.lark.imcollab.common.domain;

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
public class TaskEvent implements Serializable {
    private String eventId;
    private String taskId;
    private String stepId;
    private TaskEventType type;
    private String payload;
    private Instant occurredAt;
}
