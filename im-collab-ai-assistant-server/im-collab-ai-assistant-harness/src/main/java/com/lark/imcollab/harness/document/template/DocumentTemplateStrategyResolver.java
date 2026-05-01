package com.lark.imcollab.harness.document.template;

import com.lark.imcollab.common.domain.Task;
import com.lark.imcollab.common.domain.TaskType;
import com.lark.imcollab.common.model.entity.ExecutionContract;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class DocumentTemplateStrategyResolver {

    private static final List<TemplateRule> TEMPLATE_RULES = List.of(
            new TemplateRule(DocumentTemplateType.MEETING_SUMMARY, List.of("会议纪要", "会议总结", "复盘纪要", "会议")),
            new TemplateRule(DocumentTemplateType.REQUIREMENTS, List.of("需求文档", "需求分析", "prd", "产品需求", "需求")),
            new TemplateRule(DocumentTemplateType.TECHNICAL_ARCHITECTURE, List.of("架构设计", "架构方案", "harness架构", "harness 模块", "技术架构", "系统架构", "模块架构")),
            new TemplateRule(DocumentTemplateType.TECHNICAL_PLAN, List.of("技术方案", "实施方案", "设计方案", "落地方案", "方案设计", "技术设计")),
            new TemplateRule(DocumentTemplateType.REPORT, List.of("汇报", "周报", "月报", "报告", "总结"))
    );

    public DocumentTemplateType resolve(ExecutionContract contract) {
        if (contract == null) {
            return DocumentTemplateType.REPORT;
        }
        DocumentTemplateType explicitType = parseExplicitType(contract.getTemplateStrategy());
        if (explicitType != null) {
            return explicitType;
        }
        return inferFromText(
                contract.getClarifiedInstruction(),
                contract.getTaskBrief(),
                contract.getRawInstruction(),
                contract.getPrimaryArtifact()
        );
    }

    public DocumentTemplateType resolve(Task task) {
        if (task == null) {
            return DocumentTemplateType.REPORT;
        }
        if (task.getExecutionContract() != null) {
            DocumentTemplateType fromContract = resolve(task.getExecutionContract());
            if (fromContract != DocumentTemplateType.REPORT || task.getType() == TaskType.WRITE_DOC) {
                return fromContract;
            }
        }
        return inferFromText(task.getClarifiedInstruction(), task.getTaskBrief(), task.getRawInstruction(), task.getType() == null ? "" : task.getType().name());
    }

    private DocumentTemplateType inferFromText(String... values) {
        String text = String.join(" ", values == null ? List.of() : List.of(values)).toLowerCase(Locale.ROOT);
        for (TemplateRule rule : TEMPLATE_RULES) {
            if (rule.matches(text)) {
                return rule.templateType();
            }
        }
        if (text.contains("架构") || text.contains("设计") || text.contains("方案") || text.contains("harness")) {
            return DocumentTemplateType.TECHNICAL_ARCHITECTURE;
        }
        return DocumentTemplateType.REPORT;
    }

    private DocumentTemplateType parseExplicitType(String templateStrategy) {
        if (templateStrategy == null || templateStrategy.isBlank()) {
            return null;
        }
        try {
            return DocumentTemplateType.valueOf(templateStrategy.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private record TemplateRule(DocumentTemplateType templateType, List<String> keywords) {
        private boolean matches(String text) {
            return keywords.stream().filter(keyword -> keyword != null && !keyword.isBlank()).anyMatch(text::contains);
        }
    }
}
