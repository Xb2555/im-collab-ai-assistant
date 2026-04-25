package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import com.lark.imcollab.common.model.enums.SubtaskStatusEnum;
import lombok.Data;

@Data
public class SubtaskPlan {
    /** 子任务 ID */
    private String subtaskId;
    /** 子任务类型 */
    private OutputTargetEnum type;
    /** 子任务状态 */
    private SubtaskStatusEnum status;
}
