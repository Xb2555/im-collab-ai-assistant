package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.RequireInput;
import com.lark.imcollab.common.model.entity.TermResolution;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TermDisambiguationService {

    private final TermDisambiguationPolicyRegistry policyRegistry;

    public TermDisambiguationService(TermDisambiguationPolicyRegistry policyRegistry) {
        this.policyRegistry = policyRegistry;
    }

    public DisambiguationOutcome resolve(
            PlanTaskSession session,
            String rawInstruction,
            WorkspaceContext workspaceContext
    ) {
        for (TermDisambiguationPolicy policy : policyRegistry.getPolicies()) {
            var outcome = policy.evaluate(session, rawInstruction, workspaceContext);
            if (outcome.isPresent()) {
                return outcome.get();
            }
        }
        return new DisambiguationOutcome(List.of(), null);
    }

    public record DisambiguationOutcome(List<TermResolution> resolutions, RequireInput requireInput) {
    }
}
