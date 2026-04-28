package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.TaskCommandTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskCommand implements Serializable {

    private String commandId;
    private TaskCommandTypeEnum type;
    private String taskId;
    private String conversationKey;
    private String rawText;
    private WorkspaceContext workspaceContext;
    private String idempotencyKey;
}
