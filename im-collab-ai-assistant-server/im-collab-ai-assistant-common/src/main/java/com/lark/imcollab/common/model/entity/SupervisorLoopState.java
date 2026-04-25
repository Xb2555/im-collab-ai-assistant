package com.lark.imcollab.common.model.entity;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class SupervisorLoopState {

    /** 任务 ID */
    private String taskId;

    /** 当前任务意图 */
    private TaskIntent taskIntent;

    /** 当前路由与产出快照 */
    private RoutePacket routePacket;

    /** 循环消息轨迹 */
    private List<LoopMessage> messageTrace;

    /** 当前轮次 */
    private Integer turnCount;

    /** 最大轮次上限 */
    private Integer maxTurns;

    /** 输出截断恢复次数 */
    private Integer maxOutputTokensRecoveryCount;

    /** 是否已尝试响应式压缩 */
    private Boolean hasAttemptedReactiveCompact;

    /** 待汇总的工具调用信息 */
    private String pendingToolUseSummary;

    /** 本轮流转原因 */
    private String transitionReason;

    /** 终止原因 */
    private String terminalReason;

    /** 是否被外部中断 */
    private volatile boolean aborted;

    /** 错误信息 */
    private String errorMsg;

    public SupervisorLoopState() {
        this.messageTrace = new ArrayList<>();
        this.turnCount = 1;
        this.maxOutputTokensRecoveryCount = 0;
        this.hasAttemptedReactiveCompact = false;
    }
}
