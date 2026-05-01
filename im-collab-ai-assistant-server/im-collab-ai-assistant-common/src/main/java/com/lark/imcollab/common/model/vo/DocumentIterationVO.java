package com.lark.imcollab.common.model.vo;

import com.lark.imcollab.common.model.entity.DocumentEditPlan;
import com.lark.imcollab.common.model.enums.DocumentIterationIntentType;
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
@Schema(description = "文档迭代响应")
public class DocumentIterationVO implements Serializable {
    private String taskId;
    private String planningPhase;
    private DocumentIterationIntentType recognizedIntent;
    private boolean requireInput;
    private String preview;
    private String docUrl;
    private List<String> modifiedBlocks;
    private String summary;
    private DocumentEditPlan editPlan;
}
