package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import lombok.Data;

import java.util.List;

@Data
public class RoutePacket {
    private String taskId;
    private TaskIntent intent;
    private OutputTargetEnum target;
    private boolean aiResolved;
    private List<SubtaskPlan> subtasks;
    private String transitionReason;
}
