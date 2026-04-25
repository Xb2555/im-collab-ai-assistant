package com.lark.imcollab.common.model.entity;

import lombok.Data;

import java.util.List;

@Data
public class SupervisorResult {
    /** 任务 ID */
    private String taskId;
    /** 终止原因 */
    private String terminalReason;
    /** 子任务结果列表 */
    private List<SubtaskPlan> subtasks;
    /** 文档产物链接 */
    private String docUrl;
    /** PPT 产物链接 */
    private String pptUrl;
    /** 错误信息 */
    private String errorMsg;
}
