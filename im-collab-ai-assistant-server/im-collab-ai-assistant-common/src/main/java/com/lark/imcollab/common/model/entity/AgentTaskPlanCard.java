package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.AgentTaskTypeEnum;
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
public class AgentTaskPlanCard implements Serializable {

    private String taskId;

    private String parentCardId;

    private AgentTaskTypeEnum taskType;

    @Builder.Default
    private String status = "PENDING";

    private String input;

    private String output;

    private List<String> tools;

    private String context;
}
