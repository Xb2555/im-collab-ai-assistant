package com.lark.imcollab.common.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskRuntimeSnapshot implements Serializable {

    private TaskRecord task;
    private List<TaskStepRecord> steps;
    private List<ArtifactRecord> artifacts;
    private List<TaskEventRecord> events;
}
