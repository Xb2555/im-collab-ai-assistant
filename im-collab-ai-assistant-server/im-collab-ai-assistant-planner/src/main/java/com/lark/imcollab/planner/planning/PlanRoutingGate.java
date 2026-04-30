package com.lark.imcollab.planner.planning;

import com.lark.imcollab.common.model.entity.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class PlanRoutingGate {

    public PlanRoutingDecision decide(String rawInstruction, WorkspaceContext workspaceContext) {
        String instruction = normalize(rawInstruction);
        List<String> reasons = new ArrayList<>();
        if (instruction.isBlank()) {
            return new PlanRoutingDecision(PlanRoute.CLARIFY, false, true, false, List.of("blank input"));
        }

        collectContextReasons(instruction, workspaceContext, reasons);
        boolean needsContextCollection = !reasons.isEmpty();

        List<String> deepPlanningReasons = new ArrayList<>();
        collectDeepPlanningReasons(instruction, deepPlanningReasons);
        if (!deepPlanningReasons.isEmpty()) {
            reasons.addAll(deepPlanningReasons);
        }

        if (needsContextCollection) {
            return new PlanRoutingDecision(PlanRoute.COLLECT_CONTEXT, true, false, false, List.copyOf(reasons));
        }
        if (!deepPlanningReasons.isEmpty()) {
            return new PlanRoutingDecision(PlanRoute.DEEP_PLANNING, false, false, false, List.copyOf(reasons));
        }
        if (isVague(instruction)) {
            return new PlanRoutingDecision(PlanRoute.CLARIFY, false, true, false, List.of("vague task goal"));
        }
        return new PlanRoutingDecision(PlanRoute.FAST_PLAN, false, false, true, List.of("simple explicit deliverable"));
    }

    private void collectContextReasons(String instruction, WorkspaceContext workspaceContext, List<String> reasons) {
        if (containsAny(instruction, "自动收集", "收集", "拉取", "搜索", "检索", "查找", "读取", "整合资料", "资料收集")) {
            reasons.add("requires collecting external or workspace context");
        }
        if (containsAny(instruction, "最近两周", "最近一周", "近两周", "历史决策", "历史记录", "群聊", "聊天记录", "项目文档", "相关文档", "附件")) {
            reasons.add("references historical messages or documents");
        }
        if (workspaceContext == null) {
            return;
        }
        if (size(workspaceContext.getSelectedMessages()) > 3 || size(workspaceContext.getSelectedMessageIds()) > 3) {
            reasons.add("contains multiple selected messages");
        }
        if (size(workspaceContext.getDocRefs()) > 1) {
            reasons.add("contains multiple document references");
        }
        if (size(workspaceContext.getAttachmentRefs()) > 0) {
            reasons.add("contains attachment references");
        }
        if (hasText(workspaceContext.getTimeRange()) && containsAny(normalize(workspaceContext.getTimeRange()), "周", "月", "week", "month")) {
            reasons.add("contains broad time range");
        }
    }

    private void collectDeepPlanningReasons(String instruction, List<String> reasons) {
        if (containsAny(instruction, "对比", "比较", "三种方案", "多个方案", "方案选择", "决策分析")) {
            reasons.add("requires comparing alternatives");
        }
        if (containsAny(instruction, "评估", "成本", "风险", "资源依赖", "优先级", "取舍", "策略", "推进策略")) {
            reasons.add("requires strategic analysis");
        }
        if (containsAny(instruction, "老板关注点", "管理层关注", "决策建议", "判断", "结论建议")) {
            reasons.add("requires judgment for stakeholders");
        }
    }

    private boolean isVague(String instruction) {
        return instruction.length() < 5
                || containsAny(instruction, "帮我做一下", "弄一下", "搞一下", "整理一下")
                && !containsAny(instruction, "文档", "ppt", "摘要", "报告", "方案", "mermaid");
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(normalize(value))) {
                return true;
            }
        }
        return false;
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
