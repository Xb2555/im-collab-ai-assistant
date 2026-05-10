# Doc Agent 接入完成态产物修改说明

这份文档只说明 Doc Agent 如何接入现有“已完成任务产物修改”链路，以及必须遵守的接口契约。Doc Agent 内部怎么做定位、改写、校验、审批，不在这里约束。

## 1. 现有入口

前端和 IM 不新增入口，完成态产物修改统一走 Planner 命令：

```http
POST /api/planner/tasks/{taskId}/commands
```

DOC 修改请求示例：

```json
{
  "action": "REPLAN",
  "feedback": "把这份文档补充一段风险分析",
  "artifactPolicy": "EDIT_EXISTING",
  "targetArtifactId": "doc-artifact-id",
  "version": 3
}
```

Planner 已经负责：

1. 判断这是完成态任务调整。
2. 判断用户是“修改已有产物”还是“保留旧版重新生成”。
3. 多个完成任务时让用户先选任务。
4. 多个产物时让用户先选产物。
5. 根据 `targetArtifactId` 定位目标 artifact。

Doc Agent 不需要处理这些选择和入口分流，只需要提供“修改指定 DOC artifact”的能力。

## 2. Doc Agent 需要暴露的能力

建议通过 common facade 暴露一个稳定黑盒能力，命名可再商量：

```java
public interface DocumentArtifactIterationFacade {
    DocumentArtifactIterationResult edit(DocumentArtifactIterationRequest request);
}
```

请求字段契约：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `taskId` | 是 | 原完成态任务 ID。 |
| `artifactId` | 是 | 要修改的 DOC artifact ID。 |
| `docUrl` | 是 | 原飞书文档链接。 |
| `instruction` | 是 | 用户自然语言修改要求。 |
| `operatorOpenId` | 否 | 操作者 openId。 |
| `workspaceContext` | 否 | 上下文信息，按现有模型透传。 |

响应字段契约：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `taskId` | 是 | 必须等于请求里的原任务 ID。 |
| `artifactId` | 是 | 必须等于请求里的原 artifact ID。 |
| `docUrl` | 是 | 原文档链接，不能换成新文档。 |
| `status` | 是 | `COMPLETED` / `WAITING_APPROVAL` / `FAILED`。 |
| `requireInput` | 是 | 是否需要用户确认。 |
| `summary` | 是 | 给用户看的结果摘要。 |
| `preview` | 否 | 写入 artifact preview 的短说明。 |
| `modifiedBlocks` | 否 | 受影响 block 列表。 |
| `approvalPayload` | 否 | 需要确认时给 GUI 展示的编辑计划。 |

## 3. 成功修改契约

Doc Agent 修改原文档成功时，必须返回：

```json
{
  "taskId": "task-id",
  "artifactId": "doc-artifact-id",
  "docUrl": "https://xxx.feishu.cn/docx/xxx",
  "status": "COMPLETED",
  "requireInput": false,
  "summary": "已完成文档改写，影响位置数：2",
  "preview": "已补充风险分析章节",
  "modifiedBlocks": ["block-1", "block-2"]
}
```

必须遵守：

1. 不创建新 `taskId`。
2. 不创建新 DOC 文档。
3. 不新增 DOC artifact。
4. 不改变原 artifact 的 `artifactId`。
5. 不改变原 artifact 的 `url`。
6. `summary` 必须可直接展示给用户。

Planner 收到成功结果后会：

1. 将原 artifact 标记为 `UPDATED`。
2. 更新原 artifact 的 `preview/version/updatedAt`。
3. 追加 `ARTIFACT_UPDATED` 和 `TASK_COMPLETED` 事件。
4. 保持任务为 `COMPLETED`。
5. 让 `/runtime.actions.canReplan=true`。

## 4. 需要用户确认的契约

如果 Doc Agent 认为修改风险较高，需要用户确认编辑计划，返回：

```json
{
  "taskId": "task-id",
  "artifactId": "doc-artifact-id",
  "docUrl": "https://xxx.feishu.cn/docx/xxx",
  "status": "WAITING_APPROVAL",
  "requireInput": true,
  "summary": "已生成受控编辑计划，等待你确认",
  "approvalPayload": {
    "riskLevel": "HIGH",
    "targetPreview": "原文片段...",
    "generatedContent": "拟写入内容..."
  }
}
```

必须遵守：

1. `requireInput=true` 时，不要实际写入最终修改，除非你们内部明确支持“先改后审”。
2. `summary` 要能直接作为追问文案展示。
3. `approvalPayload` 尽量包含前端展示编辑计划所需信息。

Planner 收到该结果后会：

1. 将 session 置为 `ASK_USER`。
2. 让 `actions.canResume=true`。
3. 不把 artifact 标记为 `UPDATED`。
4. 不发 `TASK_COMPLETED`。

如果一期暂不接审批链路，可以先约定完成态 DOC 修改只返回 `COMPLETED` 或 `FAILED`，不要返回 `WAITING_APPROVAL`。

## 5. 失败契约

修改失败时，Doc Agent 可以抛异常或返回：

```json
{
  "taskId": "task-id",
  "artifactId": "doc-artifact-id",
  "docUrl": "https://xxx.feishu.cn/docx/xxx",
  "status": "FAILED",
  "requireInput": false,
  "summary": "文档修改失败：未找到目标段落"
}
```

必须遵守：

1. 失败不能报告成成功。
2. 如果原文档没有确认修改完成，不要返回 `COMPLETED`。
3. 不要新增 artifact 记录失败结果。
4. `summary` 要告诉用户失败原因。

Planner 收到失败后会保留旧 artifact，不会把它标成 `UPDATED`。

## 6. Planner 接入方式

Planner 当前 DOC 分支暂时是“未接入提示”。Doc Agent 提供 facade 后，把 DOC 分支改成调用：

```java
result = documentArtifactIterationFacade.edit(request);
```

请求由 Planner 组装：

```java
DocumentArtifactIterationRequest request = DocumentArtifactIterationRequest.builder()
        .taskId(session.getTaskId())
        .artifactId(artifact.getArtifactId())
        .docUrl(artifact.getUrl())
        .instruction(userInstruction)
        .operatorOpenId(operatorOpenId)
        .workspaceContext(workspaceContext)
        .build();
```

Doc Agent 不需要关心：

1. 用户来自 GUI 还是 IM。
2. 当前聊天下有几个完成任务。
3. 当前任务下有几个产物。
4. 用户选择的是第几个产物。
5. 重新生成新版文档的链路。

## 7. 和“重新生成”的边界

Doc Agent 只负责“修改原文档”。

| 用户意图 | 谁处理 |
| --- | --- |
| 修改这份文档、补充一段、润色已有文档 | Doc Agent 原地修改 |
| 保留旧版，再生成一份新版文档 | Planner 重新规划 + 正常执行链路 |
| 整体重新规划并重跑任务 | Planner 重新规划 + 用户确认执行 |

所以 Doc Agent 不要在原地修改接口里创建新版文档；如果用户要新版，Planner 会走另一条链路。

## 8. 必守边界

接入时不要改这些约定：

1. GUI 入口仍是 `/planner/tasks/{taskId}/commands`。
2. IM 入口仍走现有 Planner 对话链路。
3. 完成态修改不切换 `taskId`。
4. 原地修改不新增同类型最终 artifact。
5. `artifactPolicy=EDIT_EXISTING` 表示倾向修改已有产物。
6. `artifactPolicy=KEEP_EXISTING_CREATE_NEW` 表示保留旧产物并新建一版。
7. 多任务选择、多产物选择由 Planner 负责。
8. PPT 原地编辑链路不要改。

## 9. 验收标准

接入完成后至少验证：

1. GUI 创建 DOC 任务并执行完成。
2. GUI 对完成任务发起 DOC 修改。
3. 原飞书文档 URL 不变，内容已变化。
4. `/runtime` 中 DOC artifact 数量不增加。
5. 原 DOC artifact 的 `status=UPDATED`。
6. 原 DOC artifact 的 `preview` 有本次修改摘要。
7. 任务仍为 `COMPLETED`，`actions.canReplan=true`。
8. IM 里说“修改刚才那份文档”也能走同一条链路。
