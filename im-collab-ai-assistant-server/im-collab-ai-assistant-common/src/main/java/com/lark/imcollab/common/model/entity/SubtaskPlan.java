package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import com.lark.imcollab.common.model.enums.SubtaskStatusEnum;
import lombok.Data;

@Data
public class SubtaskPlan {
    private String subtaskId;
    private OutputTargetEnum type;
    private SubtaskStatusEnum status;
}
