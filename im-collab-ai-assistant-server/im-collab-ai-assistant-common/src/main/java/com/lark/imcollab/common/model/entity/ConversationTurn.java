package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.common.model.enums.TaskIntakeTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "任务级 Planner 对话记忆条目")
public class ConversationTurn implements Serializable {

    @Schema(description = "轮次ID")
    private String turnId;

    @Schema(description = "角色：USER/ASSISTANT/SYSTEM")
    private String role;

    @Schema(description = "内容")
    private String content;

    @Schema(description = "来源：GUI/IM/PLANNER")
    private String source;

    @Schema(description = "当时规划阶段")
    private PlanningPhaseEnum phase;

    @Schema(description = "当时入口意图")
    private TaskIntakeTypeEnum intakeType;

    @Schema(description = "创建时间")
    private Instant createdAt;
}
