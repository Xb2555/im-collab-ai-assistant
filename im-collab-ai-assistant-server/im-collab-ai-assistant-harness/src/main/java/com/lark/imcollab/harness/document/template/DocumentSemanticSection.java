package com.lark.imcollab.harness.document.template;

import java.util.List;

public enum DocumentSemanticSection {
    BACKGROUND(List.of("项目背景", "背景与问题", "背景与上下文", "会议背景", "背景")),
    GOAL(List.of("设计目标与非目标", "目标与范围", "会议目标", "目标")),
    PRINCIPLES(List.of("设计原则", "架构原则", "目标与设计原则", "原则")),
    PLAN(List.of("模块分层与职责", "模块分层", "总体架构", "执行方案", "方案", "关键结论")),
    RISKS(List.of("风险与边界", "风险与依赖", "待确认事项", "风险")),
    OWNERS(List.of("演进建议", "责任分工", "分工", "行动项")),
    TIMELINE(List.of("实施节奏与时间计划", "时间计划", "下一步安排", "时间"));

    private final List<String> aliases;

    DocumentSemanticSection(List<String> aliases) {
        this.aliases = aliases;
    }

    public List<String> aliases() {
        return aliases;
    }
}
