package com.lark.imcollab.planner.prompt;

public final class PromptContextKeys {

    private PromptContextKeys() {}

    public static final String TASK_ID = "prompt.taskId";
    public static final String AGENT_NAME = "_AGENT_";
    public static final String PHASE = "prompt.phase";
    public static final String RAW_INSTRUCTION = "prompt.rawInstruction";
    public static final String CONTEXT = "prompt.context";
    public static final String CONVERSATION_MEMORY = "prompt.conversationMemory";
    public static final String CLARIFICATION_ANSWERS = "prompt.clarificationAnswers";
    public static final String SUBMISSION_TASK_ID = "prompt.submission.taskId";
    public static final String SUBMISSION_AGENT_TASK_ID = "prompt.submission.agentTaskId";
    public static final String SUBMISSION_STATUS = "prompt.submission.status";
    public static final String SUBMISSION_RAW_OUTPUT = "prompt.submission.rawOutput";
    public static final String NEXT_STEP_TASK_GOAL = "prompt.nextStep.taskGoal";
    public static final String NEXT_STEP_CLARIFIED_GOAL = "prompt.nextStep.clarifiedGoal";
    public static final String NEXT_STEP_PLAN_CARDS = "prompt.nextStep.planCards";
    public static final String NEXT_STEP_COMPLETED_STEPS = "prompt.nextStep.completedSteps";
    public static final String NEXT_STEP_ARTIFACTS = "prompt.nextStep.artifacts";
    public static final String NEXT_STEP_SUPPORTED_CAPABILITIES = "prompt.nextStep.supportedCapabilities";
    public static final String NEXT_STEP_CANDIDATE_ACTIONS = "prompt.nextStep.candidateActions";
}
