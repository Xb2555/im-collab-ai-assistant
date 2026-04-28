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
@Schema(description = "缁撴瀯鍖栨剰鍥惧揩鐓?")
public class IntentSnapshot implements Serializable {

    @Schema(description = "鐢ㄦ埛鐩爣")
    private String userGoal;

    @Schema(description = "浜や粯鐗╃洰鏍囧垪琛?")
    private List<String> deliverableTargets;

    @Schema(description = "鏉ユ簮鑼冨洿")
    private WorkspaceContext sourceScope;

    @Schema(description = "鏃堕棿鑼冨洿")
    private String timeRange;

    @Schema(description = "鐩爣鍙椾紬")
    private String audience;

    @Schema(description = "绾︽潫鏉′欢")
    private List<String> constraints;

    @Schema(description = "缂哄け妲戒綅")
    private List<String> missingSlots;

    @Schema(description = "鍦烘櫙璺緞")
    private List<ScenarioCodeEnum> scenarioPath;
}
