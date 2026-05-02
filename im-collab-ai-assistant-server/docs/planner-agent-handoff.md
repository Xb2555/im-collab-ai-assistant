# Planner Agent Handoff Context

Last updated: 2026-05-01

This document is a compact handoff for another agent/window to continue work on the Planner/Agent orchestration part of this repository.

## Repository Context

- Repository root: `/Users/xb2555/.codex/worktrees/3e3f/im-collab-ai-assistant/im-collab-ai-assistant-server`
- Project type: Java 17 Maven multi-module Spring Boot project.
- Product goal: Agent-Pilot competition project, from Feishu IM conversation to document/PPT deliverables with a full task loop.
- Core product principle from `AGENTS.md`: **AI Agent is the main driver; GUI is the dashboard and auxiliary control panel.**
- Scenario mapping:
  - A: IM intent/instruction entry.
  - B: Task understanding and planning.
  - C: Document or whiteboard generation/editing.
  - D: Presentation or canvas generation/editing.
  - E: Multi-end collaboration, consistency, conflict handling.
  - F: Summary, delivery, archive.

## Current User Priority

The user is currently dissatisfied with the flexibility of the existing Planner implementation.

The desired direction is:

- Move away from a rigid `SupervisorPlannerService`-centric chain.
- Introduce a **main control agent** that can supervise and coordinate specialist agents.
- Let the main agent judge:
  - whether the user intent is clear,
  - whether enough context exists to complete the task,
  - whether clarification is needed,
  - how to adjust plans without losing prior context,
  - how to monitor task state and report progress.
- Prefer framework-native agent orchestration using **Spring AI Alibaba Agent Framework** instead of hand-rolling arbitrary custom agent runtime code.

The latest explicit technical direction:

- Reference `https://java2ai.com/docs`.
- Use Spring AI Alibaba Agent Framework as much as possible.
- Do not invent a random custom agent framework if the official framework already provides an equivalent pattern.

## Important Existing Modules

### Planner

Module: `im-collab-ai-assistant-planner`

Current role:

- Task understanding.
- Clarification.
- Planning.
- Replanning.
- Plan gate.
- Runtime projection.
- Execution contract generation.

Important classes/services observed:

- `SupervisorPlannerService`
  - Still too heavy.
  - Coordinates plan/resume/adjust flows.
  - Contains orchestration logic that should eventually move behind a framework-native supervisor agent.
- `PlannerConversationService`
  - Entry orchestration/facade-level conversation handling.
- `TaskIntakeService`
  - Intake wrapper, historically involved in routing user input.
- `IntentRouterService`
  - Hard rules first, LLM constrained classification, guard fallback.
- `ClarificationDecisionService`
  - LLM-based clarification decision.
  - Should remain semantic-first, with rules only for safety/state protection.
- `TaskPlanningService`
  - Builds `PlanBlueprint -> TaskPlanGraph -> ExecutionContract`.
- `PlanGateService`
  - Validates final plan executability.
- `PlanRoutingGate`
  - Currently uses heuristic routing; should be reduced or moved into agent/tool decision flow where possible.
- `PlanAdjustmentInterpreter`
  - Interprets local replan/patch intent.
- `CardPlanPatchMerger`
  - Merges local plan patches while preserving existing plan cards.
- `PlannerConversationMemoryService`
  - Task-scoped conversation memory.
  - Intended to make a task conversation continuous across clarification, replan, query, and confirmation.
- `TaskRuntimeProjectionService`
  - Projects planner state into runtime models for GUI/IM progress display.
- `FastPlanBlueprintFactory`
  - Generates deterministic fast plans for simple/common DOC/PPT/SUMMARY tasks.

### Common Models

Module: `im-collab-ai-assistant-common`

Important contracts:

- `PlanTaskSession`
- `TaskRecord`
- `TaskStepRecord`
- `ArtifactRecord`
- `TaskEventRecord`
- `TaskCommand`
- `TaskRuntimeSnapshot`
- `TaskPlanGraph`
- `ExecutionContract`

The GUI and IM should read task progress from runtime models, not from transient planner internals.

### Store

Module: `im-collab-ai-assistant-store`

Important store:

- `PlannerStateStore`
- `RedisPlannerStateStore`

Responsibilities:

- Store sessions.
- Store task runtime records.
- Maintain owner task indexes for GUI "my tasks" view.
- Support task-level runtime snapshot and events.

### IM Gateway

Module: `im-collab-ai-assistant-imGateWay`

Important responsibilities:

- Feishu IM message intake.
- Route messages to planner.
- Format natural IM replies.
- Avoid dumping full GUI cards into chat unless user asks for detailed plan.

Important classes:

- `LoggingLarkInboundMessageDispatcher`
- `LarkIMTaskReplyFormatter`
- `LarkIMPlannerReviewNotifier`
- `LarkUserProfileHydrationService`

Known desired IM behavior:

- Short plan confirmation in IM.
- Detailed plan only when user asks.
- Natural clarification and failure messages.
- "开始执行 / 没问题，执行" should confirm execution and move task to executing.
- Query status should only read runtime, not trigger replan.

### App

Module: `im-collab-ai-assistant-app`

Important responsibilities:

- HTTP planner controller.
- GUI auth/OAuth.
- GUI task list and task detail APIs.
- Implementation adapter for IM task commands.

Important classes:

- `PlannerController`
- `DefaultImTaskCommandFacade`
- `TaskRuntimeViewAssembler`

### Harness / Document Execution

Module: `im-collab-ai-assistant-harness`

Important note:

- The user explicitly did not want further harness changes during recent planner work.
- Planner changes must not break B -> C document generation.

Existing B -> C chain:

`PlannerController / IM dispatcher -> Planner -> ExecutionContract -> HarnessFacade -> DocumentExecutionService -> DocumentWorkflowNodes -> LarkDocTool`

Document chain compatibility requirements:

- Planner should still generate `DOC_CREATE` or `DOC_DRAFT` steps for document tasks.
- `TaskBridgeService` / legacy task adapter compatibility should be preserved unless explicitly redesigned.
- `DocumentExecutionSupport.findPrimaryDocStep()` semantics should not be broken.

## Current Architecture Problem

The current planner has improved but remains structurally awkward:

- Too much control logic remains in `SupervisorPlannerService`.
- Some routing still depends on local heuristics and special cases.
- LLM calls are split across intent, clarification, planning, and replan services, but they do not feel like one coherent agent state machine.
- Replan can still feel brittle when user messages are informal.
- Task memory exists but needs stronger integration into all semantic decisions.
- Planner currently mixes:
  - conversation state,
  - clarification decisions,
  - planning decisions,
  - plan patch decisions,
  - runtime state projection,
  - failure handling.

The desired architecture is a framework-native **Planner Supervisor Agent** that coordinates tools and specialist agents, with code-level gates for safety and state consistency.

## Spring AI Alibaba Agent Framework Direction

Official docs to follow:

- Main docs: `https://java2ai.com/docs`
- Agent tutorials: `https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents/`
- Tool tutorials: `https://java2ai.com/docs/frameworks/agent-framework/tutorials/tools/`
- Multi-agent docs: `https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent/`
- Agent tool docs: `https://java2ai.com/docs/frameworks/agent-framework/advanced/agent-tool/`
- Memory docs: `https://java2ai.com/docs/frameworks/agent-framework/advanced/memory/`

Existing framework usage:

- `AgentFrameworkConfig` already defines several `ReactAgent` beans:
  - `clarificationAgent`
  - `intentAgent`
  - `unknownIntentReplyAgent`
  - `planningAgent`
  - `supervisorAgent`
- `SubAgentInterceptor`, `SummarizationHook`, and `CheckpointSaverProvider` are already present.
- Harness already uses framework-style agents and graph:
  - `DocumentAgentConfig`
  - `SequentialAgent documentGenerationSequence`
  - `DocumentWorkflowConfig`
  - `StateGraph`
  - `CompiledGraph`
  - checkpoint saver

Preferred target direction:

- Keep public APIs stable.
- Build a Planner supervisor using official framework primitives.
- Expose local planner operations as tools.
- Let the supervisor choose tools/subagents, while code gates validate final state.

## Proposed Target Planner Shape

### Main Agent

`PlannerSupervisorAgent`

Role:

- Own task-level planning conversation.
- Decide whether to clarify, plan, replan, query status, or confirm execution.
- Use task memory and current runtime state.
- Never execute document/PPT work directly.
- Never claim an artifact has been generated unless runtime/artifact records say so.

### Specialist Agents

Prefer Spring AI Alibaba `ReactAgent` / official multi-agent patterns:

- `ClarificationAgent`
  - Decide whether information is missing.
  - Generate 1-3 natural clarification questions.
- `ContextReadinessAgent`
  - Decide if current context is enough to plan.
  - Future hook for content collection subagent.
- `IntentAgent`
  - Choose from fixed intent enum only:
    - `START_TASK`
    - `ANSWER_CLARIFICATION`
    - `ADJUST_PLAN`
    - `QUERY_STATUS`
    - `CONFIRM_ACTION`
    - `CANCEL_TASK`
    - `UNKNOWN`
- `PlanningAgent`
  - Generate supported plan structure only.
  - Supported outputs currently: `DOC`, `PPT`, `SUMMARY`.
  - Mermaid is a content requirement inside DOC, not a separate artifact/whiteboard step.
- `ReplannerAgent`
  - Generate local patch intent only.
  - Should not rewrite the entire plan unless user explicitly asks to restart/replan all.
- `StatusNarratorAgent`
  - Generate natural IM/GUI text based on runtime state.
  - Should not trigger planning/replanning.

### Framework Tools

Expose deterministic operations as tools:

- `RuntimeQueryTool`
  - Read `TaskRuntimeSnapshot`.
- `RuntimeProjectionTool`
  - Project plan/session phase into `TaskRecord`, `TaskStepRecord`, `TaskEventRecord`.
- `PlanGateTool`
  - Validate supported artifacts/workers/dependencies.
- `PlanMergeTool`
  - Merge local patch into existing plan.
- `ExecutionConfirmTool`
  - Confirm execution and call existing app/harness adapter.
- `AskUserTool`
  - Mark session as `ASK_USER` and record clarification questions.
- `DetailedPlanTool`
  - Return complete plan for explicit "完整计划 / 详细计划" queries.

Code should keep safety gates:

- Cancel/confirm/status hard rules are still allowed.
- ASK_USER phase ordinary input is treated as clarification answer.
- Plan gate blocks unsupported artifact/worker/step types.
- Version/owner checks protect GUI and multi-end consistency.

## Current Supported Planner Capability Boundary

Stable supported outputs:

- DOC
- PPT
- SUMMARY

Current stable workers:

- `doc-create-worker`
- `ppt-create-worker`
- `summary-worker`

Important policy:

- Planner should not invent unsupported workers.
- Planner should not output whiteboard/canvas/tool steps unless those workers are explicitly implemented.
- Mermaid should be represented as a requirement inside a DOC step.
- If the user asks for unsupported output, planner should clarify or map to supported outputs.

## Important UX Rules Already Established

### IM

- Do not paste full runtime JSON or full card details into chat by default.
- Plan-ready reply should show 2-4 key steps and ask for confirmation.
- "详细计划 / 完整计划 / 计划给我看看" should show full plan.
- "任务概况 / 进度怎么样" should show status, not trigger replan.
- "这个方案还行" should be accepted as approval-like feedback, not a failed intent.
- Technical failures should be translated into understandable user messages.

### GUI

- GUI is a dashboard.
- GUI should show user's own tasks only.
- Task list cards come from `TaskRecord`.
- Task detail comes from `TaskRuntimeSnapshot`.
- Steps come from `TaskStepRecord`.
- Timeline comes from `TaskEventRecord`.
- GUI should not hardcode task sequence logic.

### Planner Runtime Events

Useful event/stage names used or expected:

- `INTAKE_ACCEPTED`
- `INTENT_ROUTING`
- `CLARIFICATION_REQUIRED`
- `PLANNING_STARTED`
- `PLAN_GATE_CHECKING`
- `PLAN_READY`
- `PLAN_ADJUSTED`
- `EXECUTING`
- `FAILED`

## Known Issues / Pain Points From Recent Tests

These are not all necessarily still present, but they drove recent changes:

- Replan sometimes lost existing DOC/PPT steps when adding a new step.
- "顺手补一个风险表" once replaced the document with a risk table instead of appending.
- "完整的计划给我看看" was misrouted as status or unknown instead of detailed plan.
- Clarification replies like "文档" were not always connected to the previous question.
- Duplicate prefix appeared: "我还需要确认一下：我还需要确认一下：..."
- Some failure messages exposed internal errors such as duplicate step ids.
- IM execution confirmation once logged `EXECUTING` but did not send a visible response.
- Failed harness execution did not reliably notify the user.
- Lark outbound message uuid had max length 50; idempotency keys must stay within Feishu limits.
- DeepSeek/Spring AI issue appeared:
  - `reasoning_content` in thinking mode must be passed back.
  - Solution direction was to avoid incompatible reasoning mode or configure model accordingly.

## Current GUI / Auth Work Notes

Implemented or discussed:

- OAuth callback and JWT login flow.
- GUI "my tasks" list by owner openId.
- IM-created tasks should be visible in GUI when the same openId logs in.
- `Authorization: Bearer <token>` is required for GUI task APIs.
- `GET /api/planner/tasks?limit=10` returns 401 when token is missing or malformed.

Do not put real JWTs, app secrets, or Feishu tokens in docs or commits.

## Frontend Contract Notes

The frontend asked for:

- Task list and active task APIs.
- Task details with history and realtime status.
- SSE for per-task events.
- Version field for optimistic locking.
- Command endpoint with version conflict protection.
- `selectedMessages` text array support.
- `PLAN_READY` maps to frontend `REVIEWING_PLAN`.
- `ASK_USER` maps to frontend `WAITING_USER`.

Known interface doc:

- `docs/gui-planner-api.md`

Keep this document updated when API behavior changes.

## Test Commands

Common verification commands:

```bash
mvn -pl im-collab-ai-assistant-planner -am test
mvn -pl im-collab-ai-assistant-app -am test
mvn -pl im-collab-ai-assistant-imGateWay -am test
mvn clean test
```

The repo guideline says to run:

```bash
./mvnw clean test
```

before final submission when feasible.

## Suggested Next Implementation Plan

1. Inspect current Spring AI Alibaba dependency version and available supervisor/multi-agent APIs.
2. Verify whether the project can use official `SupervisorAgent` / `AgentTool` directly.
3. If available, introduce `PlannerSupervisorAgentConfig` using framework-native supervisor/multi-agent primitives.
4. Wrap existing deterministic planner operations as framework tools:
   - runtime query,
   - plan merge,
   - plan gate,
   - clarification state update,
   - execution confirm.
5. Keep external planner API compatibility through graph-facing facades/services; do not reintroduce `SupervisorPlannerService`.
6. Move semantic decision prompts into specialist agents, not large Java keyword branches.
7. Keep hard rules only for:
   - cancellation,
   - execution confirmation,
   - status query,
   - owner/version checks,
   - ASK_USER answer absorption,
   - final plan gate.
8. Add tests around conversation continuity:
   - ambiguous request -> clarification,
   - short answer -> plan,
   - add step -> patch without losing existing cards,
   - detailed plan query -> no replan,
   - status query -> runtime only,
   - confirm execution -> existing B -> C chain still works.

## 2026-05-01 Graph Convergence Update

The latest direction has been implemented as the new baseline:

- `PlannerConversationService` now requires `PlannerSupervisorGraphRunner`; the old `handleWithLegacySwitch` path is removed.
- `SupervisorPlannerService`, old `PlannerFacade`, `IntentRouter`, `PlanRoutingGate`, and `FastPlanBlueprintFactory` have been removed from the code path.
- `PlannerController` routes commands through `PlannerCommandApplicationService`, which delegates to `PlannerSupervisorGraphRunner`.
- `PlannerSupervisorGraphNodes` no longer falls back to a legacy supervisor; graph nodes call focused node services and tools.
- `AgentFrameworkConfig` exposes sub agents through Spring AI Alibaba `AgentTool`; the duplicate `SubAgentInterceptor` bean was removed.
- The old thin legacy classes `TaskPlanner`, `Replanner`, and `PlanGate` were removed from the planner module.
- GUI `CONFIRM_EXECUTE` and `CANCEL` commands now route through the graph compatibility facade rather than directly orchestrating harness/runtime in the controller.
- Runtime event types now include `CONTEXT_CHECKING` and `PLAN_REVIEWING` so GUI/IM can see more of the planner graph progress.

Remaining recommended cleanup:

1. Keep `PlanningNodeService`, `ReplanNodeService`, `ClarificationNodeService`, `ReviewGateNodeService`, `ContextNodeService`, and `ReadOnlyNodeService` focused; do not rebuild a new large supervisor service.
2. Move `contextCollectorAgent` and `planReviewAgent` parsing to fully structured graph node outputs if the framework version supports stable output schema for those agents.
3. Continue shrinking local semantic fallback code in replan/intent paths; model semantics should live in constrained agents, while Java keeps state-machine and gate protection.

## User Requirements To Preserve

These are product and architecture requirements the user has repeated several times. Future planner work should treat them as persistent constraints, not one-off chat context.

1. The planner should feel like a flexible main-control agent, not a rigid if/else chain. The main agent decides whether to clarify, collect context, plan, replan, read status, confirm execution, or cancel.
2. Use Spring AI Alibaba framework primitives first: `StateGraph` for deterministic workflow, `ReactAgent` for agent reasoning, `AgentTool` for specialist agents, and `ToolCallback` / `methodTools` for deterministic tools. Do not introduce a new custom agent framework when the official framework can express the pattern.
3. The `claude-code-haha-main` project is an architecture reference only: borrow the pattern of main agent, tools, sub agents, task progress, and notification responsibility. Do not copy TypeScript implementation code into this Java project.
4. Keep `StateGraph` as the single planner main path. Do not reintroduce `SupervisorPlannerService` or legacy rule fast paths.
5. Avoid large keyword tables. Keep hard rules only for safety and state-machine protection: cancel, confirm execution, status/detail query, owner/version checks, ASK_USER answer absorption, and final gate validation.
6. Let LLM handle semantic flexibility inside fixed contracts. Intent must choose from the fixed enum; replan must output a local patch intent; clarification must output structured ASK_USER/READY decisions.
7. Replan is local by default. Adding, deleting, updating, or reordering steps must preserve unrelated existing cards, artifacts, and original task goals. Full replacement requires explicit user wording such as "全部重做" or "重新规划".
8. Planner currently supports stable deliverables `DOC`, `PPT`, and `SUMMARY`. Mermaid is a content requirement inside DOC, not an independent chart/whiteboard artifact. Do not plan unsupported workers just because the model suggests them.
9. Planner is responsible for understanding, context sufficiency checks, clarification, planning, plan adjustment, waiting for confirmation, and monitoring task state. It must not claim that documents/PPTs/summaries have already been generated.
10. Runtime is the single source of truth for GUI and IM: `TaskRecord`, `TaskStepRecord`, `ArtifactRecord`, `TaskEventRecord`, and `TaskRuntimeSnapshot`. IM replies should be formatted from runtime state, not from raw agent thoughts.
11. Query operations such as "进度怎么样", "完整计划给我看看", "任务概况", and "已有产物" are read-only. They must not trigger planning/replanning and must not increment task version.
12. GUI is the dashboard and auxiliary control panel; AI Agent is the main driver. GUI must not own planner business logic, and planner must keep user-visible progress/events updated for GUI and IM.
13. Do not change harness, document workflow, or Feishu document generation while optimizing planner unless explicitly requested. Preserve B -> C compatibility through the existing execution bridge.
14. User-facing IM copy should be natural and concise. Unknown or unsupported requests should be answered like a collaborator, not with mechanical fallback text or internal errors such as JSON, stepId, or gate exceptions.
15. Tests should cover continuous conversations, not only single isolated calls: clarification answer continuity, local replan, read-only detail query, version conflict, confirm execution, runtime status, and B -> C regression.

## Claude Code Reference Requirements

When changing planner architecture docs or designing the next planner implementation step, first compare against the local reference project:

`/Users/xb2555/vsCodeProjects/claude-code-haha-main`

Use these reference points:

1. `CLAUDE.md`
   - Use its architectural split as the planner documentation pattern: tool system, multi-agent architecture, service layer, state/data flow, commands/skills/plugins.
   - Planner docs should explain the Java equivalent of each relevant layer instead of only listing service classes.
2. `src/Tool.ts`
   - Use `ToolUseContext`, `ToolResult`, progress callbacks, notifications, permission context, and subagent context separation as the conceptual model.
   - Java planner tools should document their equivalent context boundary: taskId, owner, phase, memory, runtime snapshot, allowed actions, deterministic output.
3. `src/tasks.ts`
   - Use the task registry pattern as the reference for planner runtime.
   - Java equivalent is `TaskRecord`, `TaskStepRecord`, `TaskEventRecord`, `ArtifactRecord`, plus worker/tool dispatch contracts.
4. `src/tools/shared/spawnMultiAgent.ts`
   - Use it as the reference for sub-agent spawning semantics: explicit agent type/name, inherited context, permission/safety settings, parent-child lineage, progress registration, and lifecycle cleanup.
   - Java equivalent should be Spring AI Alibaba `AgentTool` plus `RunnableConfig.threadId` scoped as `{taskId}:planner:{agentName}`.
5. `docs/03-tool-system.png`, `docs/04-multi-agent.png`, and `docs/08-state-data-flow.png`
   - Use these diagrams as documentation inspiration when adding planner diagrams. The target docs should show tool boundaries, sub-agent relationships, and state flow from IM/GUI input to runtime/progress/artifacts.

Required mapping in future planner docs:

| Claude Code reference idea | Java planner equivalent |
| --- | --- |
| Main loop / lead agent | `PlannerSupervisorDecisionAgent` inside Spring AI Alibaba `StateGraph` |
| Tool system | `PlannerMemoryTool`, `PlannerRuntimeTool`, `PlannerContextTool`, `PlannerQuestionTool`, `PlannerPatchTool`, `PlannerGateTool`, `PlannerExecutionTool` |
| AgentTool / teammate agent | specialist Spring AI Alibaba agents exposed through `AgentTool` |
| Task registry | `TaskRecord`, `TaskStepRecord`, `ArtifactRecord`, `TaskEventRecord` |
| Tool progress / notifications | planner runtime events and IM/GUI notification formatter |
| Permission and safety layer | owner/version checks, hard-rule safety actions, `PlanGateService`, capability policy |
| Session memory | task-scoped `PlanTaskSession.conversationTurns/conversationSummary` and framework threadId/checkpoint |

Do not use the reference project as a reason to add unrelated features. The current product boundary remains Feishu IM -> planner -> doc/PPT/summary execution loop.

## Non-Goals Unless Explicitly Asked

- Do not rewrite harness/document workflow.
- Do not change real Feishu document generation chain unless the user explicitly asks.
- Do not add unsupported workers just because the model can imagine them.
- Do not move GUI logic into planner.
- Do not rely on full conversation history in chat; update docs/tests instead.

## Security / Commit Notes

- Never commit secrets, app credentials, JWTs, Feishu tokens, or production config.
- User previously requested docs not to be committed in one specific commit; check current git intent before staging docs.
- Conventional commits are preferred:
  - `feat:`
  - `fix:`
  - `refactor:`
  - `docs:`
