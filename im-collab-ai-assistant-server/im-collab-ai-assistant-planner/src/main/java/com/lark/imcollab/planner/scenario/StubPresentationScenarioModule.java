package com.lark.imcollab.planner.scenario;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.ScenarioExecutionRequest;
import com.lark.imcollab.common.model.entity.ScenarioIntegrationHook;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.scenario.ScenarioModule;
import org.springframework.stereotype.Component;

@Component
public class StubPresentationScenarioModule implements ScenarioModule {

    @Override
    public ScenarioCodeEnum scenarioCode() {
        return ScenarioCodeEnum.D_PRESENTATION;
    }

    @Override
    public boolean supports(PlanBlueprint blueprint) {
        if (blueprint == null || blueprint.getPlanCards() == null) {
            return false;
        }
        return blueprint.getPlanCards().stream()
                .map(UserPlanCard::getType)
                .anyMatch(type -> type == PlanCardTypeEnum.PPT);
    }

    @Override
    public ScenarioIntegrationHook buildHook(PlanBlueprint blueprint) {
        return ScenarioIntegrationHook.builder()
                .scenarioCode(scenarioCode())
                .moduleName("stub-presentation-scenario-module")
                .status("PENDING")
                .build();
    }

    @Override
    public ScenarioExecutionRequest prepareExecutionRequest(PlanBlueprint blueprint) {
        return ScenarioExecutionRequest.builder()
                .scenarioCode(scenarioCode())
                .planBlueprint(blueprint)
                .build();
    }
}
