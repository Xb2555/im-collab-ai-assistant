package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import lombok.Data;
import java.util.List;

@Data
public class TaskIntent {
    private String rawInstruction;
    private InputSourceEnum inputSource;
    private OutputTargetEnum outputTarget;
    private Integer pageLimit;
    private String styleRequirement;
    private String reportTo;
    private List<String> keyConstraints;
}
