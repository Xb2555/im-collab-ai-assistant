package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.TaskPlanGraph;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.StepTypeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class PlanGraphBuilder {

    public TaskPlanGraph build(String taskId, PlanBlueprint blueprint) {
        PlanBlueprint source = blueprint == null ? new PlanBlueprint() : blueprint;
        return TaskPlanGraph.builder()
                .taskId(taskId)
                .goal(source.getTaskBrief())
                .deliverables(resolveDeliverables(source))
                .successCriteria(source.getSuccessCriteria() == null ? List.of() : source.getSuccessCriteria())
                .risks(source.getRisks() == null ? List.of() : source.getRisks())
                .steps(buildSteps(taskId, source.getPlanCards()))
                .build();
    }

    private List<String> resolveDeliverables(PlanBlueprint source) {
        LinkedHashSet<String> deliverables = new LinkedHashSet<>();
        for (UserPlanCard card : source.getPlanCards() == null ? List.<UserPlanCard>of() : source.getPlanCards()) {
            if (card != null && card.getType() != null && !"SUPERSEDED".equalsIgnoreCase(card.getStatus())) {
                deliverables.add(card.getType().name());
            }
        }
        if (!deliverables.isEmpty()) {
            return List.copyOf(deliverables);
        }
        for (String deliverable : source.getDeliverables() == null ? List.<String>of() : source.getDeliverables()) {
            String normalized = normalizeDeliverable(deliverable);
            if (hasText(normalized)) {
                deliverables.add(normalized);
            }
        }
        return List.copyOf(deliverables);
    }

    private String normalizeDeliverable(String value) {
        if (!hasText(value)) {
            return null;
        }
        String text = value.trim();
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("PPT") || upper.contains("SLIDE") || text.contains("幻灯") || text.contains("演示稿")) {
            return "PPT";
        }
        if (upper.contains("SUMMARY") || text.contains("摘要") || text.contains("总结") || text.contains("话术")
                || text.contains("文案")) {
            return "SUMMARY";
        }
        if (upper.contains("DOC") || text.contains("文档") || text.contains("方案") || text.contains("报告")
                || text.contains("纪要") || text.contains("材料")) {
            return "DOC";
        }
        return upper;
    }

    private List<TaskStepRecord> buildSteps(String taskId, List<UserPlanCard> planCards) {
        if (planCards == null || planCards.isEmpty()) {
            return List.of();
        }
        List<TaskStepRecord> steps = new ArrayList<>();
        for (UserPlanCard card : planCards) {
            if (card == null) {
                continue;
            }
            steps.add(TaskStepRecord.builder()
                    .stepId(hasText(card.getCardId()) ? card.getCardId() : UUID.randomUUID().toString())
                    .taskId(taskId)
                    .type(mapStepType(card.getType()))
                    .name(hasText(card.getTitle()) ? card.getTitle().trim() : "Untitled step")
                    .status(mapStepStatus(card.getStatus()))
                    .inputSummary(card.getDescription())
                    .outputSummary(null)
                    .assignedWorker(mapWorker(card.getType()))
                    .dependsOn(card.getDependsOn() == null ? List.of() : card.getDependsOn())
                    .retryCount(0)
                    .progress(card.getProgress())
                    .version(card.getVersion())
                    .build());
        }
        return steps;
    }

    private StepTypeEnum mapStepType(PlanCardTypeEnum type) {
        if (type == PlanCardTypeEnum.PPT) {
            return StepTypeEnum.PPT_CREATE;
        }
        if (type == PlanCardTypeEnum.SUMMARY) {
            return StepTypeEnum.SUMMARY;
        }
        return StepTypeEnum.DOC_CREATE;
    }

    private StepStatusEnum mapStepStatus(String status) {
        if (!hasText(status)) {
            return StepStatusEnum.READY;
        }
        return switch (status.trim().toUpperCase()) {
            case "RUNNING" -> StepStatusEnum.RUNNING;
            case "COMPLETED" -> StepStatusEnum.COMPLETED;
            case "FAILED" -> StepStatusEnum.FAILED;
            case "SKIPPED" -> StepStatusEnum.SKIPPED;
            case "SUPERSEDED" -> StepStatusEnum.SUPERSEDED;
            default -> StepStatusEnum.READY;
        };
    }

    private String mapWorker(PlanCardTypeEnum type) {
        if (type == PlanCardTypeEnum.PPT) {
            return "ppt-create-worker";
        }
        if (type == PlanCardTypeEnum.SUMMARY) {
            return "summary-worker";
        }
        return "doc-create-worker";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
