package com.lark.imcollab.common.model.entity;

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

    private String eventType;

    private String payload;

    @Builder.Default
    private Instant timestamp = Instant.now();
}
