# Planner 下一阶段优化建议

## 1. 当前判断

当前 planner 已经达到比赛项目中“可演示、可解释、可回归”的第一阶段标准：

1. IM/GUI 都能发起任务。
2. 计划可以快速生成，常见任务走 fast path。
3. REPLAN 已经从整包重做收敛为局部 patch。
4. runtime/cards/session 的主要状态已经可查。
5. 失败、取消、未知任务、非法命令等 GUI 边界已经有基本保护。
6. DeepSeek 默认使用非 thinking 模型，避免 tool-call 链路触发 `reasoning_content` 协议错误。

因此下一阶段不建议继续大拆 planner 主链路，而应围绕“真实比赛体验”做增量增强。

## 2. 优先级 P0：比赛前必须守住

### 2.1 Runtime 一致性回归

所有用户可见状态必须以 runtime 为事实源：

1. `TaskRecord.status/currentStage` 与 `PlanTaskSession.planningPhase` 保持一致。
2. `TaskStepRecord` 与 plan cards 保持一致。
3. `TaskEventRecord` 覆盖关键节点：接单、规划、调整、确认、取消、失败、完成。
4. 取消、失败、重试、修改计划后必须重新查 `/runtime` 做验证。

验收方式：

```bash
curl /api/planner/tasks/{taskId}
curl /api/planner/tasks/{taskId}/runtime
curl /api/planner/tasks/{taskId}/cards
```

三者对外展示的阶段、步骤数量、可操作按钮必须一致。

### 2.2 错误信息产品化

禁止把内部错误直接返回给 GUI 或 IM：

1. 不暴露 Java stack trace。
2. 不暴露 `NullPointerException`、`duplicate stepId`、OpenAPI 原始 JSON。
3. 对用户输出稳定业务错误，例如：
   - `40000`：请求参数错误。
   - `40400`：任务不存在。
   - `40900`：版本冲突。
   - `50001`：执行失败，可重试。

失败时必须保留 runtime event，方便 GUI 时间线展示。

### 2.3 真实回归脚本化

当前已经多次手动 curl，下一阶段应沉淀成脚本：

```text
scripts/planner-gui-smoke.sh
scripts/planner-im-smoke.md
```

至少覆盖：

1. 新建计划。
2. 查询 runtime。
3. 查询 cards。
4. SSE 收到事件。
5. REPLAN 成功。
6. version conflict。
7. unknown task。
8. cancel 后 runtime 为 `CANCELLED/ABORTED`。

这样比赛前每次改 planner 都能快速确认主链路没有回退。

## 3. 优先级 P1：体验明显提升

### 3.1 内容收集子 Agent

这是下一阶段最值得做的能力。

Planner 不应该自己去读 IM 历史、文档、会议纪要或 Base，而应新增一个 `ContextCollectorAgent`：

输入：

1. 用户目标。
2. 当前 plan draft。
3. workspace context。
4. 可用工具清单。

输出：

1. `CollectedContext`：结构化资料摘要。
2. `SourceRef`：资料来源，例如 IM 消息、文档链接、会议纪要。
3. `MissingContext`：仍缺的信息。
4. `Confidence`：资料是否足够支撑执行。

建议流程：

```text
用户输入
-> PlannerSupervisorGraphRunner
-> context_check
-> ContextCollectionGate
-> ContextCollectorAgent
-> TaskPlanningService
-> PlanGate
-> RuntimeProjection
```

注意：

1. 内容收集要有短超时。
2. 收集不到资料时不要卡死，可以进入澄清。
3. 资料来源必须能回显给 GUI，方便用户信任生成结果。
4. 不要让 content collector 直接生成最终文档，它只负责拉取和整理上下文。

### 3.2 Plan Confidence 与缺口展示

计划生成后建议增加质量分：

1. `confidence`：计划是否足够明确。
2. `missingInputs`：缺哪些资料。
3. `assumptions`：planner 做了哪些默认假设。
4. `riskFlags`：当前风险。

GUI 可以展示为“计划可信度/资料缺口”，IM 只做简短提示：

```text
我可以先按现有信息推进，但原始项目材料还不完整。你也可以补充相关文档链接。
```

### 3.3 计划确认体验

现在 `PLAN_READY` 后用户确认即可执行。下一阶段可以增加更细的确认：

1. 普通步骤默认无需逐项确认。
2. 高风险步骤需要确认，例如发群消息、覆盖文档、删除内容、公开分享。
3. GUI 可允许用户只确认部分步骤。

对应新增 gate：

```text
ActionGateService
PublishGateService
```

## 4. 优先级 P2：架构演进

### 4.1 ExecutionOrchestrator 独立化

目前 planner 已经在向 orchestrator 收敛，但执行编排仍可以继续清晰化：

1. planner 只产出计划图和执行契约。
2. orchestrator 只根据 `TaskStepRecord` 状态调度下一步。
3. worker 只消费 `TaskExecutionContext`，输出 `StepExecutionResult`。
4. 文档/PPT/摘要 worker 不反向依赖 planner 会话。

这能让 C/D/F 场景接入更自然。

### 4.2 多任务管理

当前底层可并行多个 task，但 IM 同一会话默认绑定一个活跃任务。

后续要支持：

1. “新开一个任务”显式创建新 task。
2. “查看所有任务”返回任务列表。
3. “切到第二个任务”切换 active task。
4. 回复里带短任务标识，例如 `#A12F`。

这属于体验增强，不是当前 planner 稳定性的阻塞项。

### 4.3 事件模型收敛

当前同时存在 session event 和 runtime event。后续建议：

1. GUI/SSE 统一消费 runtime event。
2. session event 只作为兼容或内部日志。
3. 事件 payload 统一结构，避免有时是字符串、有时是整段 graph JSON。
4. `PLAN_READY/PLAN_ADJUSTED` 的大 JSON 可以改成 artifact/ref 或摘要，减少 SSE 负载。

## 5. 优先级 P3：质量与可观测性

### 5.1 Planner 质量评测集

建立一组稳定输入，覆盖：

1. DOC/PPT/SUMMARY 常见任务。
2. 模糊任务，需要澄清。
3. 连续 REPLAN。
4. 删除、重排、改交付物。
5. 查询状态和完整计划。
6. 失败重试。

每次改 planner 后跑评测，统计：

1. 是否生成正确步骤。
2. 是否误触发 replan。
3. 是否丢失原步骤。
4. 是否产生重复 id。
5. 总耗时。

### 5.2 耗时分段监控

保留并完善分段日志：

1. intent routing。
2. context collection。
3. planning。
4. plan gate。
5. runtime projection。
6. replan patch。

每段都记录耗时和是否调用 LLM。这样才能判断“慢”到底来自模型、工具、Redis、飞书 OpenAPI 还是代码链路。

### 5.3 LLM 降级策略

所有 LLM 调用都应有：

1. 短超时。
2. 单次调用上限。
3. 结构化解析失败 fallback。
4. 不破坏原计划的保守策略。

尤其是：

1. intent LLM 失败 -> `UNKNOWN` 或状态安全默认。
2. replan LLM 失败 -> 澄清或本地保守 patch。
3. planning LLM 失败 -> deterministic fallback 或 `PLAN_FAILED` 友好提示。

## 6. 暂不建议做的事

以下能力看起来高级，但当前不建议马上做：

1. 复杂 DAG 并发调度。
2. 全自动多任务抢占。
3. 让 planner 直接编辑真实文档。
4. 全量切换到 reasoning/thinking 模型。
5. 在 IM 里展示完整 runtime 或大段产物。
6. 为每个自然语言表达添加硬编码关键词。

原因：

这些改动容易破坏当前已经稳定的 planner 体验，且比赛演示收益不一定高。

## 7. 下一轮推荐路线

推荐顺序：

1. 先把 GUI/IM smoke 测试脚本化。
2. 再做 `ContextCollectorAgent`。
3. 再把 collected context 接入 planner prompt 和 execution contract。
4. 然后完善 plan confidence / missing context 展示。
5. 最后再做多任务 IM 管理和 orchestrator 独立化。

这样最符合当前项目目标：Agent 是主驾驶，GUI 是仪表盘，IM 是自然协作入口。
