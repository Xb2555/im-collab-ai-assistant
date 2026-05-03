package com.lark.imcollab.planner.intent;

import com.lark.imcollab.planner.config.PlannerProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TermDisambiguationPolicyRegistry {

    private final List<TermDisambiguationPolicy> policies;

    public TermDisambiguationPolicyRegistry(PlannerProperties properties, LlmChoiceResolver choiceResolver) {
        this.policies = properties.getDisambiguation().getTermPolicies().stream()
                .map(definition -> new ConfigurableTermDisambiguationPolicy(definition, choiceResolver))
                .map(policy -> (TermDisambiguationPolicy) policy)
                .toList();
    }

    public List<TermDisambiguationPolicy> getPolicies() {
        return policies;
    }
}
