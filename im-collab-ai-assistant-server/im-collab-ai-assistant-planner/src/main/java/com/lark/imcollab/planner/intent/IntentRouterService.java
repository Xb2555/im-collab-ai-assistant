package com.lark.imcollab.planner.intent;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskCommand;
import com.lark.imcollab.planner.config.PlannerProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class IntentRouterService {

    private final HardRuleIntentClassifier hardRuleClassifier;
    private final LlmIntentClassifier llmIntentClassifier;
    private final IntentDecisionGuard decisionGuard;
    private final PlannerProperties plannerProperties;

    @Autowired
    public IntentRouterService(
            HardRuleIntentClassifier hardRuleClassifier,
            LlmIntentClassifier llmIntentClassifier,
            IntentDecisionGuard decisionGuard,
            PlannerProperties plannerProperties
    ) {
        this.hardRuleClassifier = hardRuleClassifier;
        this.llmIntentClassifier = llmIntentClassifier;
        this.decisionGuard = decisionGuard;
        this.plannerProperties = plannerProperties;
    }

    public IntentRouterService() {
        this.hardRuleClassifier = new HardRuleIntentClassifier();
        this.llmIntentClassifier = null;
        this.plannerProperties = new PlannerProperties();
        this.decisionGuard = new IntentDecisionGuard(plannerProperties);
    }

    public TaskCommand route(
            PlanTaskSession session,
            String rawInstruction,
            String userFeedback,
            boolean existingSession
    ) {
        String effectiveInput = firstText(userFeedback, rawInstruction);
        IntentRoutingResult result = classify(session, effectiveInput, existingSession);
        return TaskCommand.builder()
                .commandId(UUID.randomUUID().toString())
                .type(result.type())
                .taskId(session == null ? null : session.getTaskId())
                .rawText(result.normalizedInput())
                .idempotencyKey(buildIdempotencyKey(session, result.normalizedInput()))
                .build();
    }

    public IntentRoutingResult classify(PlanTaskSession session, String rawInput, boolean existingSession) {
        String normalized = rawInput == null ? "" : rawInput.trim();
        Optional<IntentRoutingResult> hardRule = hardRuleClassifier.classify(session, normalized, existingSession);
        if (hardRule.isPresent()) {
            log.info("INTENT_ROUTER classify_result source=hard_rule taskId={} existingSession={} phase={} input='{}' type={} confidence={} reason='{}' normalizedInput='{}' readOnlyView={}",
                    session == null ? null : session.getTaskId(),
                    existingSession,
                    session == null ? null : session.getPlanningPhase(),
                    normalized,
                    hardRule.get().type(),
                    hardRule.get().confidence(),
                    hardRule.get().reason(),
                    hardRule.get().normalizedInput(),
                    hardRule.get().readOnlyView());
            return hardRule.get();
        }
        Optional<IntentRoutingResult> model = llmIntentClassifier == null
                ? Optional.empty()
                : llmIntentClassifier.classify(session, normalized, existingSession)
                .map(result -> decisionGuard.guard(session, normalized, existingSession, result));
        if (model.isPresent()) {
            log.info("INTENT_ROUTER classify_result source=llm taskId={} existingSession={} phase={} input='{}' type={} confidence={} reason='{}' normalizedInput='{}' readOnlyView={}",
                    session == null ? null : session.getTaskId(),
                    existingSession,
                    session == null ? null : session.getPlanningPhase(),
                    normalized,
                    model.get().type(),
                    model.get().confidence(),
                    model.get().reason(),
                    model.get().normalizedInput(),
                    model.get().readOnlyView());
            return model.get();
        }
        if (plannerProperties.getIntent().isFallbackToLocalRules()) {
            IntentRoutingResult fallback = hardRuleClassifier.fallback(session, normalized, existingSession)
                    .orElseGet(() -> model.orElse(new IntentRoutingResult(
                            com.lark.imcollab.common.model.enums.TaskCommandTypeEnum.UNKNOWN,
                            0.0d,
                            "no intent classification",
                            normalized,
                            true)));
            log.info("INTENT_ROUTER classify_result source=fallback taskId={} existingSession={} phase={} input='{}' type={} confidence={} reason='{}' normalizedInput='{}' readOnlyView={}",
                    session == null ? null : session.getTaskId(),
                    existingSession,
                    session == null ? null : session.getPlanningPhase(),
                    normalized,
                    fallback.type(),
                    fallback.confidence(),
                    fallback.reason(),
                    fallback.normalizedInput(),
                    fallback.readOnlyView());
            return fallback;
        }
        IntentRoutingResult unknown = model.orElse(new IntentRoutingResult(
                com.lark.imcollab.common.model.enums.TaskCommandTypeEnum.UNKNOWN,
                0.0d,
                "no intent classification",
                normalized,
                true));
        log.info("INTENT_ROUTER classify_result source=default_unknown taskId={} existingSession={} phase={} input='{}' type={} confidence={} reason='{}' normalizedInput='{}' readOnlyView={}",
                session == null ? null : session.getTaskId(),
                existingSession,
                session == null ? null : session.getPlanningPhase(),
                normalized,
                unknown.type(),
                unknown.confidence(),
                unknown.reason(),
                unknown.normalizedInput(),
                unknown.readOnlyView());
        return unknown;
    }

    private String firstText(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String buildIdempotencyKey(PlanTaskSession session, String input) {
        String taskId = session == null ? "new" : session.getTaskId();
        return taskId + ":" + Integer.toHexString((input == null ? "" : input).hashCode());
    }
}
