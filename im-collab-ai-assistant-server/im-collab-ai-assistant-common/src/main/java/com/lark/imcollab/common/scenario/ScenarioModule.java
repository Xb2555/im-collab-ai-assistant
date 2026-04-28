package com.lark.imcollab.common.scenario;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.ScenarioExecutionRequest;
import com.lark.imcollab.common.model.entity.ScenarioIntegrationHook;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;

public interface ScenarioModule {

    ScenarioCodeEnum scenarioCode();

    boolean supports(PlanBlueprint blueprint);

    ScenarioIntegrationHook buildHook(PlanBlueprint blueprint);

    ScenarioExecutionRequest prepareExecutionRequest(PlanBlueprint blueprint);
}
