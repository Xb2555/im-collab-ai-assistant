package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
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
public class TaskStepRecord implements Serializable {

    private String stepId;
    private String taskId;
    private StepTypeEnum type;
    private String name;
    private StepStatusEnum status;
    private String inputSummary;
    private String outputSummary;
    private String assignedWorker;
    private List<String> dependsOn;
    private int retryCount;
    private int progress;
    private int version;
    private Instant startedAt;
    private Instant endedAt;
}
