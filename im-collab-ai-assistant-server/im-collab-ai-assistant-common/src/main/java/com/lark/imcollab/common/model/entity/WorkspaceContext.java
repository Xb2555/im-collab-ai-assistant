package com.lark.imcollab.common.model.entity;

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
@Schema(description = "工作空间上下文（选中的消息、文档等）")
public class WorkspaceContext implements Serializable {

    @Schema(description = "选中内容类型（MESSAGE/DOCUMENT/FILE）")
    private String selectionType;

    @Schema(description = "时间范围筛选")
    private String timeRange;

    @Schema(description = "选中的消息内容列表")
    private List<String> selectedMessages;

    @Schema(description = "用户职业角色（如产品经理、销售、运营）")
    private String profession;

    @Schema(description = "行业领域（如教育、医疗、金融）")
    private String industry;

    @Schema(description = "目标受众（如管理层、客户、团队成员）")
    private String audience;

    @Schema(description = "偏好风格（如正式、简洁、叙事）")
    private String tone;

    @Schema(description = "输出语言（如中文、英文）")
    private String language;

    @Schema(description = "Prompt 模板配置档（如 default/pm/sales）")
    private String promptProfile;

    @Schema(description = "Prompt 模板版本（如 v1/v2/v3）")
    private String promptVersion;
}
