package com.lark.imcollab.planner.intent;

import java.util.List;

public interface LlmChoiceResolver {

    String chooseOne(String instruction, List<String> allowedChoices, String systemPrompt);
}
