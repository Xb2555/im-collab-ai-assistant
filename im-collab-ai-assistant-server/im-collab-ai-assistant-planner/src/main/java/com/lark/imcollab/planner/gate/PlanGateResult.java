package com.lark.imcollab.planner.gate;

import java.util.List;

public record PlanGateResult(boolean passed, List<String> reasons) {
}
