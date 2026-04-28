package com.lark.imcollab.planner.prompt;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.TaskSubmissionResult;
import com.lark.imcollab.planner.config.PlannerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class PlannerPromptFacade {

    private static final Logger log = LoggerFactory.getLogger(PlannerPromptFacade.class);
    private static final String PROMPT_ROOT = "prompt/";
    private static final String ROLE_DIR = "role/";
    private static final String PROFILE_DIR = "profiles/";
    private static final String LEGACY_DIR = "legacy/";

    private final PromptTemplateService templateService;
    private final PlannerProperties plannerProperties;

    public PlannerPromptFacade(
            PromptTemplateService templateService,
            PlannerProperties plannerProperties) {
        this.templateService = templateService;
        this.plannerProperties = plannerProperties;
    }

    public String supervisorPrompt(PlanTaskSession session) {
        return renderComposedPrompt("supervisor-system.md", "supervisor-examples.md", session, Map.of());
    }

    public String supervisorInstruction(PlanTaskSession session) {
        return renderRoleAware("supervisor-instruction.md", session);
    }

    public String clarificationPrompt(PlanTaskSession session) {
        return renderComposedPrompt("clarification-system.md", "clarification-examples.md", session, Map.of());
    }

    public String clarificationInstruction(PlanTaskSession session) {
        return renderRoleAware("clarification-instruction.md", session);
    }

    public String intentPrompt(PlanTaskSession session) {
        return renderComposedPrompt("intent-system.md", "intent-examples.md", session, Map.of());
    }

    public String intentInstruction(PlanTaskSession session) {
        return renderRoleAware("intent-instruction.md", session);
    }

    public String planningPrompt(PlanTaskSession session) {
        return renderComposedPrompt("planning-system.md", "planning-examples.md", session, Map.of());
    }

    public String planningInstruction(PlanTaskSession session) {
        return renderRoleAware("planning-instruction.md", session);
    }

    public String planningInstruction(
            PlanTaskSession session,
            String rawInstruction,
            String context,
            String clarificationAnswers) {
        Map<String, String> extra = new HashMap<>();
        extra.put("rawInstruction", safe(rawInstruction));
        extra.put("context", safe(context));
        extra.put("clarificationAnswers", safe(clarificationAnswers));
        return renderRoleAware("planning-instruction.md", session, extra);
    }

    public String resultJudgePrompt(PlanTaskSession session) {
        return renderComposedPrompt("result-judge-system.md", "result-judge-examples.md", session, Map.of());
    }

    public String resultJudgeInstruction(PlanTaskSession session) {
        return renderRoleAware("result-judge-instruction.md", session);
    }

    public String resultJudgeInstruction(PlanTaskSession session, TaskSubmissionResult submission) {
        Map<String, String> extra = buildSubmissionVars(submission);
        return renderRoleAware("result-judge-instruction.md", session, extra);
    }

    public String resultJudgeInstruction(PlanTaskSession session, Map<String, String> variables) {
        return renderRoleAware("result-judge-instruction.md", session, variables == null ? Map.of() : variables);
    }

    public String resultAdvicePrompt(PlanTaskSession session) {
        return renderComposedPrompt("result-advice-system.md", "result-advice-examples.md", session, Map.of());
    }

    public String resultAdviceInstruction(PlanTaskSession session) {
        return renderRoleAware("result-advice-instruction.md", session);
    }

    public String resultAdviceInstruction(PlanTaskSession session, TaskSubmissionResult submission) {
        Map<String, String> extra = buildSubmissionVars(submission);
        return renderRoleAware("result-advice-instruction.md", session, extra);
    }

    public String resultAdviceInstruction(PlanTaskSession session, Map<String, String> variables) {
        return renderRoleAware("result-advice-instruction.md", session, variables == null ? Map.of() : variables);
    }

    public String summarizationPrompt() {
        String promptPath = resolveTemplatePath(null, null, "summarization-prompt.md");
        return templateService.render(promptPath, Map.of());
    }

    private String renderRoleAware(String fileName, PlanTaskSession session) {
        return renderRoleAware(fileName, session, Map.of());
    }

    private String renderRoleAware(String fileName, PlanTaskSession session, Map<String, String> extraVariables) {
        Map<String, String> variables = new HashMap<>();
        PlannerProperties.Prompt defaults = plannerProperties.getPrompt();
        variables.put("profession", fallback(session != null ? session.getProfession() : null, defaults.getDefaultProfession()));
        variables.put("industry", fallback(session != null ? session.getIndustry() : null, defaults.getDefaultIndustry()));
        variables.put("audience", fallback(session != null ? session.getAudience() : null, defaults.getDefaultAudience()));
        variables.put("tone", fallback(session != null ? session.getTone() : null, defaults.getDefaultTone()));
        variables.put("language", fallback(session != null ? session.getLanguage() : null, defaults.getDefaultLanguage()));
        variables.putAll(extraVariables);

        String profile = fallback(session != null ? session.getPromptProfile() : null, defaults.getProfile());
        String version = fallback(session != null ? session.getPromptVersion() : null, defaults.getVersion());
        String promptPath = resolveTemplatePath(profile, version, fileName);
        return templateService.render(promptPath, variables);
    }

    private String renderComposedPrompt(
            String systemFileName,
            String examplesFileName,
            PlanTaskSession session,
            Map<String, String> extraVariables) {
        String system = renderRoleAware(systemFileName, session, extraVariables);
        String examples = tryRenderRoleAware(examplesFileName, session, extraVariables);
        if (examples.isBlank()) {
            return system;
        }
        return system + System.lineSeparator() + System.lineSeparator() + examples;
    }

    private String tryRenderRoleAware(String fileName, PlanTaskSession session, Map<String, String> extraVariables) {
        try {
            return renderRoleAware(fileName, session, extraVariables);
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }

    private String resolveTemplatePath(String profile, String version, String fileName) {
        PlannerProperties.Prompt defaults = plannerProperties.getPrompt();
        String fallbackProfile = fallback(defaults.getFallbackProfile(), "default");
        String fallbackVersion = fallback(defaults.getFallbackVersion(), "v1");
        String actualProfile = fallback(profile, defaults.getProfile());
        String actualVersion = fallback(version, defaults.getVersion());

        List<String> candidates = List.of(
                PROMPT_ROOT + PROFILE_DIR + actualProfile + "/" + actualVersion + "/" + fileName,
                PROMPT_ROOT + PROFILE_DIR + actualProfile + "/" + fileName,
                PROMPT_ROOT + PROFILE_DIR + fallbackProfile + "/" + fallbackVersion + "/" + fileName,
                PROMPT_ROOT + PROFILE_DIR + fallbackProfile + "/" + fileName,
                PROMPT_ROOT + ROLE_DIR + fileName,
                PROMPT_ROOT + LEGACY_DIR + fileName,
                PROMPT_ROOT + fileName
        );

        for (String candidate : candidates) {
            if (templateService.exists(candidate)) {
                return candidate;
            }
        }

        String detail = "profile=" + actualProfile + ",version=" + actualVersion + ",file=" + fileName;
        throw new IllegalArgumentException("No prompt template found for " + detail);
    }

    private String fallback(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        String trimmed = value.trim();
        if (!Objects.equals(trimmed, defaultValue)) {
            log.debug("Prompt variable override: {} -> {}", defaultValue, trimmed);
        }
        return trimmed;
    }

    private Map<String, String> buildSubmissionVars(TaskSubmissionResult submission) {
        Map<String, String> variables = new HashMap<>();
        if (submission == null) {
            return variables;
        }
        variables.put("taskId", safe(submission.getTaskId()));
        variables.put("agentTaskId", safe(submission.getAgentTaskId()));
        variables.put("submissionStatus", safe(submission.getStatus()));
        variables.put("rawOutput", safe(submission.getRawOutput()));
        return variables;
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
