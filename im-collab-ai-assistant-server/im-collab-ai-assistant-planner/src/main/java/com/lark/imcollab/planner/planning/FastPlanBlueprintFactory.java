package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.IntentSnapshot;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.ScenarioCodeEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class FastPlanBlueprintFactory {

    public Optional<IntentSnapshot> buildIntentSnapshot(String rawInstruction, WorkspaceContext workspaceContext) {
        String instruction = safe(rawInstruction);
        List<PlanCardTypeEnum> artifacts = detectArtifacts(instruction);
        if (artifacts.isEmpty() || isVague(instruction)) {
            return Optional.empty();
        }
        return Optional.of(IntentSnapshot.builder()
                .userGoal(instruction)
                .deliverableTargets(artifacts.stream().map(Enum::name).toList())
                .sourceScope(workspaceContext)
                .timeRange(workspaceContext == null ? null : workspaceContext.getTimeRange())
                .audience(detectAudience(instruction))
                .constraints(detectConstraints(instruction))
                .missingSlots(List.of())
                .scenarioPath(resolveScenarioPath(artifacts))
                .build());
    }

    public Optional<PlanBlueprint> buildBlueprint(String taskId, String rawInstruction, IntentSnapshot intentSnapshot) {
        String instruction = safe(rawInstruction);
        List<PlanCardTypeEnum> artifacts = detectArtifacts(instruction);
        if (artifacts.isEmpty() && intentSnapshot != null && intentSnapshot.getDeliverableTargets() != null) {
            artifacts = intentSnapshot.getDeliverableTargets().stream()
                    .map(this::toPlanCardType)
                    .flatMap(Optional::stream)
                    .toList();
        }
        if (artifacts.isEmpty() || isVague(instruction)) {
            return Optional.empty();
        }
        List<UserPlanCard> cards = buildCards(taskId, instruction, artifacts);
        return Optional.of(PlanBlueprint.builder()
                .taskBrief(instruction)
                .scenarioPath(resolveScenarioPath(artifacts))
                .deliverables(artifacts.stream().map(Enum::name).toList())
                .sourceScope(intentSnapshot == null ? null : intentSnapshot.getSourceScope())
                .constraints(detectConstraints(instruction))
                .successCriteria(buildSuccessCriteria(instruction, artifacts))
                .risks(buildRisks(instruction))
                .planCards(cards)
                .build());
    }

    public PlanBlueprint fallbackDocBlueprint(String taskId, String rawInstruction, IntentSnapshot intentSnapshot) {
        String instruction = firstNonBlank(rawInstruction, intentSnapshot == null ? null : intentSnapshot.getUserGoal(), "生成一份结构化文档");
        List<PlanCardTypeEnum> artifacts = List.of(PlanCardTypeEnum.DOC);
        return PlanBlueprint.builder()
                .taskBrief(instruction)
                .scenarioPath(resolveScenarioPath(artifacts))
                .deliverables(List.of(PlanCardTypeEnum.DOC.name()))
                .sourceScope(intentSnapshot == null ? null : intentSnapshot.getSourceScope())
                .constraints(detectConstraints(instruction))
                .successCriteria(buildSuccessCriteria(instruction, artifacts))
                .risks(buildRisks(instruction))
                .planCards(buildCards(taskId, instruction, artifacts))
                .build();
    }

    public boolean supportsFastPlan(String rawInstruction) {
        return !detectArtifacts(rawInstruction).isEmpty() && !isVague(rawInstruction);
    }

    private List<UserPlanCard> buildCards(String taskId, String instruction, List<PlanCardTypeEnum> artifacts) {
        List<UserPlanCard> cards = new ArrayList<>();
        if (artifacts.contains(PlanCardTypeEnum.DOC)) {
            cards.add(card(taskId, "card-001", docTitle(instruction), docDescription(instruction), PlanCardTypeEnum.DOC, List.of()));
        }
        if (artifacts.contains(PlanCardTypeEnum.PPT)) {
            String dependency = cards.isEmpty() ? null : cards.get(cards.size() - 1).getCardId();
            cards.add(card(taskId, nextCardId(cards), pptTitle(instruction), pptDescription(instruction), PlanCardTypeEnum.PPT,
                    dependency == null ? List.of() : List.of(dependency)));
        }
        if (artifacts.contains(PlanCardTypeEnum.SUMMARY)) {
            String dependency = cards.isEmpty() ? null : cards.get(cards.size() - 1).getCardId();
            cards.add(card(taskId, nextCardId(cards), summaryTitle(instruction), summaryDescription(instruction), PlanCardTypeEnum.SUMMARY,
                    dependency == null ? List.of() : List.of(dependency)));
        }
        return cards;
    }

    private UserPlanCard card(
            String taskId,
            String cardId,
            String title,
            String description,
            PlanCardTypeEnum type,
            List<String> dependsOn
    ) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .taskId(taskId)
                .title(title)
                .description(description)
                .type(type)
                .status("PENDING")
                .progress(0)
                .dependsOn(dependsOn)
                .availableActions(List.of("CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"))
                .build();
    }

    private List<PlanCardTypeEnum> detectArtifacts(String rawInstruction) {
        String normalized = normalize(rawInstruction);
        LinkedHashSet<PlanCardTypeEnum> artifacts = new LinkedHashSet<>();
        if (containsAny(normalized, "文档", "方案", "报告", "doc", "document", "mermaid", "架构图")) {
            artifacts.add(PlanCardTypeEnum.DOC);
        }
        if (containsAny(normalized, "ppt", "slide", "幻灯片", "演示稿", "汇报大纲", "汇报的ppt", "汇报 ppt", "老板汇报")) {
            artifacts.add(PlanCardTypeEnum.PPT);
        }
        if (containsAny(normalized, "群里", "群内", "发到群", "群聊", "项目进展摘要", "进展摘要")
                || (containsAny(normalized, "摘要", "总结", "summary") && containsAny(normalized, "输出", "生成", "最后"))) {
            artifacts.add(PlanCardTypeEnum.SUMMARY);
        }
        return new ArrayList<>(artifacts);
    }

    private boolean isVague(String rawInstruction) {
        String normalized = normalize(rawInstruction);
        if (normalized.isBlank()) {
            return true;
        }
        return normalized.length() < 5
                || containsAny(normalized, "随便做", "帮我做一下", "弄一下", "搞一下")
                && !containsAny(normalized, "文档", "ppt", "摘要", "方案", "报告", "mermaid");
    }

    private List<String> detectConstraints(String rawInstruction) {
        String normalized = normalize(rawInstruction);
        List<String> constraints = new ArrayList<>();
        if (normalized.contains("不要重做") || normalized.contains("不重做")) {
            constraints.add("不要重做已有结构");
        }
        if (normalized.contains("一页") || normalized.contains("单页")) {
            constraints.add("控制为单页或单段精简输出");
        }
        if (normalized.contains("直接发到群") || normalized.contains("可直接发")) {
            constraints.add("输出应可直接发送给目标群聊");
        }
        return constraints;
    }

    private List<String> buildSuccessCriteria(String instruction, List<PlanCardTypeEnum> artifacts) {
        List<String> criteria = new ArrayList<>();
        if (artifacts.contains(PlanCardTypeEnum.DOC)) {
            criteria.add(normalize(instruction).contains("mermaid")
                    ? "文档包含可渲染 Mermaid 代码块"
                    : "文档结构清晰，主题和结论完整");
        }
        if (artifacts.contains(PlanCardTypeEnum.PPT)) {
            criteria.add("PPT 大纲与文档或任务目标保持一致，适合汇报使用");
        }
        if (artifacts.contains(PlanCardTypeEnum.SUMMARY)) {
            criteria.add("项目进展摘要简洁明确，可直接发送到群聊");
        }
        return criteria;
    }

    private List<String> buildRisks(String instruction) {
        List<String> risks = new ArrayList<>();
        String normalized = normalize(instruction);
        if (normalized.contains("mermaid") || normalized.contains("架构图")) {
            risks.add("Mermaid 图语义需结合真实系统信息校验");
        }
        if (!containsAny(normalized, "材料", "上下文", "选中", "根据", "基于")) {
            risks.add("原始材料不足时，计划内容需要用户补充或执行阶段收集上下文");
        }
        return risks;
    }

    private List<ScenarioCodeEnum> resolveScenarioPath(List<PlanCardTypeEnum> artifacts) {
        LinkedHashSet<ScenarioCodeEnum> path = new LinkedHashSet<>();
        path.add(ScenarioCodeEnum.A_IM);
        path.add(ScenarioCodeEnum.B_PLANNING);
        if (artifacts.contains(PlanCardTypeEnum.DOC) || artifacts.contains(PlanCardTypeEnum.SUMMARY)) {
            path.add(ScenarioCodeEnum.C_DOC);
        }
        if (artifacts.contains(PlanCardTypeEnum.PPT)) {
            path.add(ScenarioCodeEnum.D_PRESENTATION);
        }
        return new ArrayList<>(path);
    }

    private Optional<PlanCardTypeEnum> toPlanCardType(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = normalize(value);
        if (normalized.contains("ppt") || normalized.contains("slide")) {
            return Optional.of(PlanCardTypeEnum.PPT);
        }
        if (normalized.contains("summary") || normalized.contains("摘要") || normalized.contains("总结")) {
            return Optional.of(PlanCardTypeEnum.SUMMARY);
        }
        if (normalized.contains("doc") || normalized.contains("文档") || normalized.contains("方案")) {
            return Optional.of(PlanCardTypeEnum.DOC);
        }
        try {
            return Optional.of(PlanCardTypeEnum.valueOf(value.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private String docTitle(String instruction) {
        if (normalize(instruction).contains("mermaid") || normalize(instruction).contains("架构图")) {
            return "生成技术方案文档（含 Mermaid 架构图）";
        }
        if (normalize(instruction).contains("报告")) {
            return "生成结构化报告";
        }
        return "生成结构化文档";
    }

    private String docDescription(String instruction) {
        if (normalize(instruction).contains("mermaid") || normalize(instruction).contains("架构图")) {
            return "基于用户输入和上下文撰写结构化技术方案文档，并内嵌 Mermaid 架构图";
        }
        return "基于用户输入和上下文撰写结构化文档，保留关键结论、背景和后续动作";
    }

    private String pptTitle(String instruction) {
        String normalized = normalize(instruction);
        if (normalized.contains("老板") || normalized.contains("管理层")) {
            return "生成面向老板汇报的 PPT 大纲";
        }
        if (normalized.contains("大纲")) {
            return "生成 PPT 大纲";
        }
        return "生成配套 PPT 初稿";
    }

    private String pptDescription(String instruction) {
        if (normalize(instruction).contains("老板") || normalize(instruction).contains("管理层")) {
            return "基于已有文档或任务目标，提炼适合老板快速汇报的 PPT 大纲";
        }
        return "基于已有文档或任务目标生成结构化 PPT 初稿，便于后续演示和评审";
    }

    private String summaryTitle(String instruction) {
        if (normalize(instruction).contains("群")) {
            return "生成群内项目进展摘要";
        }
        return "生成项目进展摘要";
    }

    private String summaryDescription(String instruction) {
        if (normalize(instruction).contains("群")) {
            return "基于已有文档和汇报材料，生成一段可直接发送到群里的项目进展摘要";
        }
        return "基于已有材料生成一段简洁的项目进展摘要";
    }

    private String detectAudience(String instruction) {
        String normalized = normalize(instruction);
        if (normalized.contains("老板") || normalized.contains("管理层")) {
            return "老板/管理层";
        }
        if (normalized.contains("技术") || normalized.contains("架构")) {
            return "技术评审人";
        }
        return null;
    }

    private String nextCardId(List<UserPlanCard> cards) {
        return "card-" + String.format("%03d", cards.size() + 1);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
