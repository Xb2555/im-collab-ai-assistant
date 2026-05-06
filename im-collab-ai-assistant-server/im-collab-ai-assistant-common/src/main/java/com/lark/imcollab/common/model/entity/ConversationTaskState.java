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
public class ConversationTaskState implements Serializable {

    private String conversationKey;
    private String activeTaskId;
    private String executingTaskId;
    private String lastCompletedTaskId;
    private Instant updatedAt;
}
