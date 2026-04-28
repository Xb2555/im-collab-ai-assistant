package com.lark.imcollab.harness.document.template;

public enum DocumentTemplateType {
    REPORT("report-template.md"),
    MEETING_SUMMARY("meeting-summary-template.md"),
    REQUIREMENTS("requirements-template.md"),
    TECHNICAL_PLAN("technical-plan-template.md");

    private final String resourceName;

    DocumentTemplateType(String resourceName) {
        this.resourceName = resourceName;
    }

    public String getResourceName() {
        return resourceName;
    }
}
