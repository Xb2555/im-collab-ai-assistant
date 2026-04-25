package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import lombok.Data;
import java.util.List;

@Data
public class TaskIntent {
    /** 原始用户指令 */
    private String rawInstruction;
    /** 输入来源 */
    private InputSourceEnum inputSource;
    /** 输出目标 */
    private OutputTargetEnum outputTarget;
    /** 页数限制 */
    private Integer pageLimit;
    /** 风格要求 */
    private String styleRequirement;
    /** 汇报对象 */
    private String reportTo;
    /** 关键约束 */
    private List<String> keyConstraints;
}
