package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.AdjustmentTargetEnum;
import com.lark.imcollab.common.model.enums.PendingInteractionTypeEnum;
import com.lark.imcollab.common.model.enums.ReplanScopeEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务入口状态")
public class TaskIntakeState implements Serializable {

    @Schema(description = "入口判定类型")
    private TaskIntakeTypeEnum intakeType;

    @Schema(description = "是否续接已有会话")
    private boolean continuedConversation;

    @Schema(description = "会话绑定键")
    private String continuationKey;

    @Schema(description = "最近一次用户输入")
    private String lastUserMessage;

    @Schema(description = "意图识别说明")
    private String routingReason;

    @Schema(description = "无法处理当前意图时面向用户的自然回复")
    private String assistantReply;

    @Schema(description = "只读查询视图，由 LLM 在固定枚举意图内细分，例如 PLAN/STATUS/ARTIFACTS")
    private String readOnlyView;

    @Schema(description = "调整目标语义，例如 RUNNING_PLAN / READY_PLAN / COMPLETED_ARTIFACT / UNKNOWN")
    private AdjustmentTargetEnum adjustmentTarget;

    @Schema(description = "当前 ASK_USER 等待的交互类型")
    private PendingInteractionTypeEnum pendingInteractionType;

    @Schema(description = "IM 多任务选择挂起态")
    private PendingTaskSelection pendingTaskSelection;

    @Schema(description = "IM 多产物选择挂起态")
    private PendingArtifactSelection pendingArtifactSelection;

    @Schema(description = "新任务与 follow-up 冲突时的用户选择挂起态")
    private PendingFollowUpConflictChoice pendingFollowUpConflictChoice;

    @Schema(description = "新开任务与继续当前任务之间的用户选择挂起态")
    private PendingCurrentTaskContinuationChoice pendingCurrentTaskContinuationChoice;

    @Schema(description = "完成态产物调整挂起指令")
    private String pendingAdjustmentInstruction;

    @Schema(description = "完成态文档审批挂起的文档迭代任务 ID")
    private String pendingDocumentIterationTaskId;

    @Schema(description = "完成态文档审批挂起的目标产物 ID")
    private String pendingDocumentArtifactId;

    @Schema(description = "完成态文档审批挂起的目标文档 URL")
    private String pendingDocumentDocUrl;

    @Schema(description = "完成态文档审批挂起摘要")
    private String pendingDocumentApprovalSummary;

    @Schema(description = "完成态文档审批挂起模式")
    private String pendingDocumentApprovalMode;

    @Schema(description = "执行中断后，当前是否允许恢复原执行流程")
    private boolean resumeOriginalExecutionAvailable;

    @Schema(description = "最近一次输入时间")
    private String lastInputAt;

    @Schema(description = "中断重规划的作用范围")
    private ReplanScopeEnum replanScope;

    @Schema(description = "tail/current-step 重规划锚点 cardId")
    private String replanAnchorCardId;

    @Schema(description = "下次执行时是否保留已有产物")
    private boolean preserveExistingArtifactsOnExecution;
}
