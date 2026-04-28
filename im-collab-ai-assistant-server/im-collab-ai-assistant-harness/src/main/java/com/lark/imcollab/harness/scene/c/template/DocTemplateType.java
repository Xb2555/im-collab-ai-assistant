package com.lark.imcollab.harness.scene.c.template;

public enum DocTemplateType {
    REPORT("report-template.md"),
    MEETING_SUMMARY("meeting-summary-template.md"),
    REQUIREMENTS("requirements-template.md"),
    TECHNICAL_PLAN("technical-plan-template.md");

    private final String resourceName;

    DocTemplateType(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourceName() {
        return resourceName;
    }
}
