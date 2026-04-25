package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import lombok.Data;

import java.util.List;

@Data
public class RoutePacket {
    /** 任务 ID */
    private String taskId;
    /** 结构化意图 */
    private TaskIntent intent;
    /** 路由目标 */
    private OutputTargetEnum target;
    /** 是否由 AI 二次识别得到目标 */
    private boolean aiResolved;
    /** 子任务规划列表 */
    private List<SubtaskPlan> subtasks;
    /** 本轮状态流转原因 */
    private String transitionReason;
    /** 生成的文档链接 */
    private String docUrl;
    /** 生成的 PPT 链接 */
    private String pptUrl;
}
