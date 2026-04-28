package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "用户任务卡片")
public class UserPlanCard implements Serializable {

    @Schema(description = "卡片ID")
    private String cardId;

    @Schema(description = "所属任务ID")
    private String taskId;

    @Schema(description = "版本号")
    @Builder.Default
    private int version = 0;

    @Schema(description = "卡片标题")
    private String title;

    @Schema(description = "卡片描述")
    private String description;

    @Schema(description = "卡片类型（DOC/PPT/SUMMARY）")
    private PlanCardTypeEnum type;

    @Schema(description = "状态（PENDING/RUNNING/COMPLETED/FAILED）")
    @Builder.Default
    private String status = "PENDING";

    @Schema(description = "完成进度（0-100）")
    @Builder.Default
    private int progress = 0;

    @Schema(description = "依赖的卡片ID列表")
    private List<String> dependsOn;

    @Schema(description = "可用动作列表")
    private List<String> availableActions;

    @Schema(description = "子任务列表")
    private List<AgentTaskPlanCard> agentTaskPlanCards;
}
