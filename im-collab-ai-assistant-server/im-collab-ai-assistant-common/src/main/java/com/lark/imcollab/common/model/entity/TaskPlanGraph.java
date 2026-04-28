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
public class TaskPlanGraph implements Serializable {

    private String taskId;
    private String goal;
    private List<TaskStepRecord> steps;
    private List<String> deliverables;
    private List<String> successCriteria;
    private List<String> risks;
}
