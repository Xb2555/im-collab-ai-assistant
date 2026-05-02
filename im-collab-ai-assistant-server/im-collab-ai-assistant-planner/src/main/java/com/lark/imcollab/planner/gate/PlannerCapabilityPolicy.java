package com.lark.imcollab.planner.gate;

import com.lark.imcollab.common.model.enums.StepTypeEnum;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class PlannerCapabilityPolicy {

    private static final Set<String> SUPPORTED_ARTIFACTS = Set.of("DOC", "PPT", "SUMMARY");
    private static final Map<StepTypeEnum, String> STEP_WORKERS = Map.of(
            StepTypeEnum.DOC_CREATE, "doc-create-worker",
            StepTypeEnum.PPT_CREATE, "ppt-create-worker",
            StepTypeEnum.SUMMARY, "summary-worker"
    );

    public boolean supportsArtifact(String artifact) {
        return normalizeArtifact(artifact)
                .map(SUPPORTED_ARTIFACTS::contains)
                .orElse(false);
    }

    public boolean supportsStep(StepTypeEnum stepType) {
        return STEP_WORKERS.containsKey(stepType);
    }

    public Optional<String> expectedWorker(StepTypeEnum stepType) {
        return Optional.ofNullable(STEP_WORKERS.get(stepType));
    }

    public Optional<String> normalizeArtifact(String artifact) {
        if (artifact == null || artifact.isBlank()) {
            return Optional.empty();
        }
        String normalized = artifact.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("SLIDE")) {
            return Optional.of("PPT");
        }
        if (normalized.contains("DOC")) {
            return Optional.of("DOC");
        }
        if (normalized.contains("SUMMARY")) {
            return Optional.of("SUMMARY");
        }
        return Optional.of(normalized);
    }
}
