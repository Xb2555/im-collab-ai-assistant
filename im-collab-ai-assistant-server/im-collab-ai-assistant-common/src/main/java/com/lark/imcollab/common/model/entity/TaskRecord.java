package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRecord implements Serializable {

    private String taskId;
    private String conversationKey;
    private String ownerOpenId;
    private String source;
    private String chatId;
    private String threadId;
    private String title;
    private String goal;
    private TaskStatusEnum status;
    private String currentStage;
    private int progress;
    private List<String> artifactIds;
    private List<String> riskFlags;
    private boolean needUserAction;
    private int version;
    private Instant createdAt;
    private Instant updatedAt;
}
