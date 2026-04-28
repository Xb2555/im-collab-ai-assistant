package com.lark.imcollab.planner.scenario;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.ScenarioExecutionRequest;
import com.lark.imcollab.common.model.entity.ScenarioIntegrationHook;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import com.lark.imcollab.common.scenario.ScenarioModule;
import org.springframework.stereotype.Component;

@Component
public class PlanningScenarioModule implements ScenarioModule {

    @Override
    public ScenarioCodeEnum scenarioCode() {
        return ScenarioCodeEnum.B_PLANNING;
    }

    @Override
    public boolean supports(PlanBlueprint blueprint) {
        return true;
    }

    @Override
    public ScenarioIntegrationHook buildHook(PlanBlueprint blueprint) {
        return ScenarioIntegrationHook.builder()
                .scenarioCode(scenarioCode())
                .moduleName("planning-scenario-module")
                .status("READY")
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
