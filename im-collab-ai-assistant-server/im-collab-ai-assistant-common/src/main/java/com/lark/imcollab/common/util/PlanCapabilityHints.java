package com.lark.imcollab.common.util;

import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class PlanCapabilityHints {

    private PlanCapabilityHints() {
    }

    public static List<String> fromPlanCards(List<UserPlanCard> cards) {
        EnumSet<PlanCardTypeEnum> types = EnumSet.noneOf(PlanCardTypeEnum.class);
        if (cards != null) {
            for (UserPlanCard card : cards) {
                if (card == null || card.getType() == null || "SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                    continue;
                }
                types.add(card.getType());
            }
        }
        return fromTypes(types);
    }

    public static List<String> fromTypes(EnumSet<PlanCardTypeEnum> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        List<String> hints = new ArrayList<>();
        if (types.contains(PlanCardTypeEnum.DOC)) {
            hints.add("文档目前支持基于已选消息、已拉取聊天记录或文档摘要生成飞书文档；可以补充章节结构、受众、详略程度、语气，以及是否加入 Mermaid 源码、风险清单或行动项。");
        }
        if (types.contains(PlanCardTypeEnum.PPT)) {
            hints.add("PPT 目前支持创建新的飞书演示稿，内容以文字、矩形、线条和列表为主；可以指定 1-10 页、受众、汇报风格和每页要点密度。");
        }
        if (types.contains(PlanCardTypeEnum.DOC) && types.contains(PlanCardTypeEnum.PPT)) {
            hints.add("文档和 PPT 可以串联执行，例如先生成文档，再基于文档摘要生成 PPT；实际执行仍以当前支持的 DOC/PPT/SUMMARY 步骤为准。");
        }
        if (types.contains(PlanCardTypeEnum.SUMMARY)) {
            hints.add("摘要目前支持基于已选或已拉取的文字材料生成；可以指定长短、面向对象、语气，以及是否输出成可直接发群的版本。");
        }
        return hints;
    }
}
