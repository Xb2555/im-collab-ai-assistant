package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
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
@Schema(description = "瀹屾暣璁″垝钃濆浘")
public class PlanBlueprint implements Serializable {

    @Schema(description = "浠诲姟鎽樿")
    private String taskBrief;

    @Schema(description = "鍦烘櫙璺緞")
    private List<ScenarioCodeEnum> scenarioPath;

    @Schema(description = "浜や粯鐗╁垪琛?")
    private List<String> deliverables;

    @Schema(description = "鏉ユ簮鑼冨洿")
    private WorkspaceContext sourceScope;

    @Schema(description = "绾︽潫鏉′欢")
    private List<String> constraints;

    @Schema(description = "鎴愬姛鏍囧噯")
    private List<String> successCriteria;

    @Schema(description = "椋庨櫓鎻愮ず")
    private List<String> risks;

    @Schema(description = "璁″垝鍗＄墖")
    private List<UserPlanCard> planCards;
}
