package com.lark.imcollab.common.util;

import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlanCapabilityHintsTest {

    @Test
    void buildsHintsFromConfirmedDeliverableTypes() {
        List<String> hints = PlanCapabilityHints.fromPlanCards(List.of(
                card(PlanCardTypeEnum.DOC),
                card(PlanCardTypeEnum.PPT)
        ));

        assertThat(hints).hasSize(3);
        assertThat(hints).anySatisfy(hint -> assertThat(hint).contains("文档目前支持基于已选消息"));
        assertThat(hints).anySatisfy(hint -> assertThat(hint).contains("PPT 目前支持创建新的飞书演示稿"));
        assertThat(hints).anySatisfy(hint -> assertThat(hint).contains("基于文档摘要生成 PPT"));
    }

    @Test
    void ignoresSupersededCards() {
        UserPlanCard supersededPpt = card(PlanCardTypeEnum.PPT);
        supersededPpt.setStatus("SUPERSEDED");

        List<String> hints = PlanCapabilityHints.fromPlanCards(List.of(
                card(PlanCardTypeEnum.DOC),
                supersededPpt
        ));

        assertThat(hints).hasSize(1);
        assertThat(hints.get(0)).contains("文档目前支持基于已选消息");
    }

    private static UserPlanCard card(PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .type(type)
                .build();
    }
}
