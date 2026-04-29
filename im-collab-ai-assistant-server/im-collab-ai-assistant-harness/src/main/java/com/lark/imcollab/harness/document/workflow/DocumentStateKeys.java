package com.lark.imcollab.harness.document.workflow;

public final class DocumentStateKeys {

    public static final String TASK_ID = "taskId";
    public static final String RAW_INSTRUCTION = "rawInstruction";
    public static final String CLARIFIED_INSTRUCTION = "clarifiedInstruction";
    public static final String EXECUTION_CONSTRAINTS = "executionConstraints";
    public static final String SOURCE_SCOPE = "sourceScope";
    public static final String ALLOWED_ARTIFACTS = "allowedArtifacts";
    public static final String CARD_ID = "cardId";
    public static final String TEMPLATE_TYPE = "templateType";
    public static final String TEMPLATE_STRATEGY = "templateStrategy";
    public static final String DIAGRAM_REQUIREMENT = "diagramRequirement";
    public static final String DIAGRAM_PLAN = "diagramPlan";
    public static final String MERMAID_DIAGRAM = "mermaidDiagram";
    public static final String USER_FEEDBACK = "userFeedback";
    public static final String OUTLINE = "outline";
    public static final String SECTION_DRAFTS = "sectionDrafts";
    public static final String REVIEW_RESULT = "reviewResult";
    public static final String SECTION_PROGRESS = "sectionProgress";
    public static final String COMPLETED_SECTION_KEYS = "completedSectionKeys";
    public static final String CURRENT_SECTION_KEY = "currentSectionKey";
    public static final String DOC_MARKDOWN = "docMarkdown";
    public static final String ARTIFACT_REFS = "artifactRefs";
    public static final String DOC_ID = "docId";
    public static final String DOC_URL = "docUrl";
    public static final String WAITING_HUMAN_REVIEW = "waitingHumanReview";
    public static final String HALTED_STAGE = "haltedStage";
    public static final String DONE_OUTLINE = "doneOutline";
    public static final String DONE_SECTIONS = "doneSections";
    public static final String DONE_REVIEW = "doneReview";
    public static final String DONE_WRITE = "doneWrite";

    private DocumentStateKeys() {
    }
}
