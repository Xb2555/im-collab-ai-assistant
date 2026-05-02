# GUI Planner 前端接口文档

本文档只描述当前后端已经实现、GUI 可以直接接入的 Planner 接口。  
默认 Base URL：

```text
http://localhost:8080/api
```

所有需要登录的接口都需要带业务 JWT：

```http
Authorization: Bearer <accessToken>
```

这里的 `accessToken` 是后端 OAuth 登录接口返回的业务 token，不是飞书 `user_access_token`。

## 1. 通用响应

所有普通 HTTP 接口统一返回：

```json
{
  "code": 0,
  "data": {},
  "message": "ok"
}
```

常见业务码：

| code | 含义 | 前端处理建议 |
| --- | --- | --- |
| `0` | 成功 | 正常处理 `data` |
| `40000` | 请求参数错误 | 提示用户检查输入 |
| `40100` | 未登录 | 跳转登录 |
| `40300` | 禁止访问 | 提示无权限 |
| `40400` | 任务不存在，或不是当前用户的任务 | 返回列表页或提示任务不可访问 |
| `40900` | version 冲突 | 刷新任务详情，保留用户输入，提示“任务已被其他端更新” |
| `50001` | 操作失败 | 展示 `message` |
| `50000` | 系统异常 | 展示通用失败提示 |

## 2. 登录接口

### 2.1 获取飞书登录 URL

```http
GET /auth/login-url
```

响应：

```json
{
  "code": 0,
  "data": {
    "authorizationUri": "https://accounts.feishu.cn/open-apis/authen/v1/authorize?...",
    "state": "random-state"
  },
  "message": "ok"
}
```

### 2.2 OAuth 回调换业务 token

```http
POST /auth/callback
Content-Type: application/json
```

请求：

```json
{
  "code": "feishu-oauth-code",
  "state": "random-state"
}
```

响应：

```json
{
  "code": 0,
  "data": {
    "accessToken": "jwt-token",
    "tokenType": "Bearer",
    "expiresIn": 7200,
    "user": {
      "openId": "ou_xxx",
      "name": "用户昵称",
      "avatarUrl": "https://..."
    }
  },
  "message": "ok"
}
```

### 2.3 查询当前用户

```http
GET /auth/me
Authorization: Bearer <accessToken>
```

响应：

```json
{
  "code": 0,
  "data": {
    "openId": "ou_xxx",
    "name": "用户昵称",
    "avatarUrl": "https://..."
  },
  "message": "ok"
}
```

### 2.4 退出登录

```http
POST /auth/logout
Authorization: Bearer <accessToken>
```

## 3. 任务列表

### 3.1 查询我的任务

```http
GET /planner/tasks?status=WAITING_APPROVAL,EXECUTING&limit=20&cursor=0
Authorization: Bearer <accessToken>
```

查询参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 否 | 任务状态过滤，支持逗号分隔 |
| `limit` | 否 | 默认 `20`，最大 `100` |
| `cursor` | 否 | 下一页游标，目前是 offset 字符串 |

任务状态：

```text
RECEIVED, CLARIFYING, PLANNING, WAITING_APPROVAL, EXECUTING,
REPLANNING, REVIEWING, PUBLISHING, COMPLETED, FAILED, CANCELLED
```

响应：

```json
{
  "code": 0,
  "data": {
    "tasks": [
      {
        "taskId": "task-id",
        "version": 2,
        "title": "生成技术方案文档和汇报 PPT",
        "goal": "根据飞书项目协作方案生成一份技术方案文档，包含 Mermaid 架构图，再生成一份汇报 PPT 初稿",
        "status": "WAITING_APPROVAL",
        "currentStage": "PLAN_READY",
        "progress": 0,
        "needUserAction": false,
        "riskFlags": [],
        "createdAt": "2026-05-02T02:00:00Z",
        "updatedAt": "2026-05-02T02:01:00Z"
      }
    ],
    "nextCursor": null
  },
  "message": "ok"
}
```

### 3.2 查询我的活跃任务

```http
GET /planner/tasks/active?limit=20&cursor=0
Authorization: Bearer <accessToken>
```

等价于查询：

```text
PLANNING, CLARIFYING, WAITING_APPROVAL, EXECUTING
```

响应结构同 `GET /planner/tasks`。

## 4. 创建计划

### 4.1 异步创建计划

GUI 正式链路使用这个接口。

```http
POST /planner/tasks/plan
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "rawInstruction": "根据飞书项目协作方案生成一份技术方案文档，包含 Mermaid 架构图，再生成一份汇报 PPT 初稿",
  "taskId": null,
  "userFeedback": null,
  "workspaceContext": {
    "selectionType": "MESSAGE",
    "selectedMessages": [
      "用户选中的 IM 消息 1",
      "用户选中的 IM 消息 2"
    ],
    "docRefs": [],
    "attachmentRefs": [],
    "timeRange": "last_7_days",
    "audience": "老板",
    "tone": "简洁正式",
    "language": "中文"
  }
}
```

说明：

- 该接口会快速返回，不等待完整 plan 生成。
- GUI 创建任务时，后端会从登录态注入当前用户 `openId`，前端不要传用户身份字段。
- 创建成功后，前端应立即进入任务详情页，调用 `/runtime` 并订阅 SSE。
- `workspaceContext.selectedMessages` 已支持字符串数组，适合 GUI 的 Cherry-Picking 消息投喂。

初始响应：

```json
{
  "code": 0,
  "data": {
    "taskId": "task-id",
    "version": 0,
    "planningPhase": "INTAKE",
    "title": "根据飞书项目协作方案生成一份技术方案文档...",
    "summary": "已收到任务，正在生成计划",
    "cards": [],
    "clarificationQuestions": [],
    "clarificationAnswers": [],
    "actions": {
      "canConfirm": false,
      "canReplan": false,
      "canCancel": true,
      "canResume": false,
      "canInterrupt": false,
      "canRetry": false
    }
  },
  "message": "ok"
}
```

### 4.2 同步创建计划

```http
POST /planner/tasks/plan/sync
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求体同 `/planner/tasks/plan`。

说明：

- 仅用于调试、Apifox、curl 回归。
- 正式 GUI 不建议使用，因为会等待完整规划结束。

## 5. 计划预览

### 5.1 获取计划预览

```http
GET /planner/tasks/{taskId}
Authorization: Bearer <accessToken>
```

响应：

```json
{
  "code": 0,
  "data": {
    "taskId": "task-id",
    "version": 1,
    "planningPhase": "PLAN_READY",
    "title": "生成技术方案文档和汇报 PPT",
    "summary": "生成技术方案文档和汇报 PPT (2 cards)",
    "cards": [
      {
        "cardId": "card-001",
        "title": "生成技术方案文档（含 Mermaid 架构图）",
        "description": "基于用户输入和上下文撰写结构化技术方案文档，并内嵌 Mermaid 架构图",
        "type": "DOC",
        "status": "pending",
        "progress": 0,
        "dependsOn": []
      },
      {
        "cardId": "card-002",
        "title": "生成汇报 PPT 初稿",
        "description": "基于技术方案文档生成结构化汇报 PPT 初稿",
        "type": "PPT",
        "status": "pending",
        "progress": 0,
        "dependsOn": ["card-001"]
      }
    ],
    "clarificationQuestions": [],
    "clarificationAnswers": [],
    "actions": {
      "canConfirm": true,
      "canReplan": true,
      "canCancel": true,
      "canResume": false,
      "canInterrupt": false,
      "canRetry": false
    }
  },
  "message": "ok"
}
```

`planningPhase` 常见值：

```text
INTAKE, ASK_USER, INTENT_READY, PLAN_READY, EXECUTING, COMPLETED, FAILED, ABORTED
```

前端展示建议：

| planningPhase | GUI 展示 |
| --- | --- |
| `INTAKE` / `INTENT_READY` | 规划中 |
| `ASK_USER` | 等待用户补充信息 |
| `PLAN_READY` | 计划审查中，等待确认 |
| `EXECUTING` | 执行中 |
| `COMPLETED` | 已完成 |
| `FAILED` | 失败 |
| `ABORTED` | 已取消 |

### 5.2 获取计划卡片

```http
GET /planner/tasks/{taskId}/cards
Authorization: Bearer <accessToken>
```

响应：

```json
{
  "code": 0,
  "data": [
    {
      "cardId": "card-001",
      "title": "生成技术方案文档（含 Mermaid 架构图）",
      "description": "基于用户输入和上下文撰写结构化技术方案文档，并内嵌 Mermaid 架构图",
      "type": "DOC",
      "status": "pending",
      "progress": 0,
      "dependsOn": []
    }
  ],
  "message": "ok"
}
```

说明：

- `/cards` 适合只渲染计划审查卡片。
- 任务详情页建议优先用 `/runtime`，因为 `/runtime` 同时包含步骤、产物和事件。

## 6. 任务运行时快照

### 6.1 获取 runtime

```http
GET /planner/tasks/{taskId}/runtime
Authorization: Bearer <accessToken>
```

这是任务详情页的主接口，建议作为 GUI 事实源。

响应：

```json
{
  "code": 0,
  "data": {
    "task": {
      "taskId": "task-id",
      "version": 2,
      "title": "生成技术方案文档和汇报 PPT",
      "goal": "根据飞书项目协作方案生成一份技术方案文档，包含 Mermaid 架构图，再生成一份汇报 PPT 初稿",
      "status": "WAITING_APPROVAL",
      "currentStage": "PLAN_READY",
      "progress": 0,
      "needUserAction": true,
      "riskFlags": [],
      "createdAt": "2026-05-02T02:00:00Z",
      "updatedAt": "2026-05-02T02:01:00Z"
    },
    "steps": [
      {
        "stepId": "card-001",
        "name": "生成技术方案文档（含 Mermaid 架构图）",
        "type": "DOC_CREATE",
        "status": "READY",
        "inputSummary": "产出结构清晰、含可渲染 Mermaid 架构图的技术方案文档",
        "outputSummary": null,
        "progress": 0,
        "retryCount": 0,
        "assignedWorker": "doc-create-worker",
        "startedAt": null,
        "endedAt": null
      }
    ],
    "artifacts": [
      {
        "artifactId": "artifact-id",
        "type": "DOC",
        "title": "飞书项目协作方案技术设计文档",
        "url": "https://...",
        "preview": "文档摘要预览",
        "status": "CREATED",
        "createdAt": "2026-05-02T02:05:00Z"
      }
    ],
    "events": [
      {
        "eventId": "event-id",
        "version": 1,
        "type": "PLAN_READY",
        "stepId": null,
        "message": "任务计划已生成",
        "createdAt": "2026-05-02T02:01:00Z"
      }
    ],
    "actions": {
      "canConfirm": true,
      "canReplan": true,
      "canCancel": true,
      "canResume": false,
      "canInterrupt": false,
      "canRetry": false
    }
  },
  "message": "ok"
}
```

`task.status` 常见值：

```text
PLANNING, CLARIFYING, WAITING_APPROVAL, EXECUTING, COMPLETED, FAILED, CANCELLED
```

`steps[].status` 常见值：

```text
READY, RUNNING, WAITING_APPROVAL, COMPLETED, FAILED, SUPERSEDED
```

`steps[].type` 常见值：

```text
DOC_DRAFT, DOC_CREATE, PPT_OUTLINE, PPT_CREATE, SUMMARY, IM_REPLY, ARCHIVE
```

前端建议：

- 顶部任务概览用 `task`。
- Stepper 用 `steps`。
- 产物区用 `artifacts`。
- 时间线用 `events`。
- 操作按钮用 `actions`。
- 调用 `/commands` 时使用 `task.version` 或最近一次 `PlanPreviewVO.version`。

## 7. SSE 实时事件

### 7.1 单任务事件流

```http
GET /planner/tasks/{taskId}/events/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

说明：

- 详情页使用。
- 当前每个 SSE 连接最多保持 10 分钟。
- 返回的是事件 JSON 字符串流。
- 建议前端收到事件后，按 `taskId` 重新拉 `/runtime`，用 `/runtime` 刷新完整页面。

事件示例：

```json
{
  "eventId": "event-id",
  "taskId": "task-id",
  "status": "PLAN_READY",
  "version": 1,
  "subtasks": [
    {
      "id": "card-001",
      "type": "DOC",
      "title": "生成技术方案文档（含 Mermaid 架构图）",
      "status": "pending"
    }
  ],
  "requireInput": null,
  "timestamp": "2026-05-02T02:01:00Z"
}
```

### 7.2 我的任务事件流

```http
GET /planner/tasks/events/stream?activeOnly=true
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

说明：

- 工作台全局监听使用。
- `activeOnly=true` 默认只推送活跃任务事件。
- `activeOnly=false` 可推送当前用户更多任务事件。
- 收到事件后建议刷新对应任务的 `/runtime` 或任务列表项。

## 8. 任务操作

所有任务操作统一走：

```http
POST /planner/tasks/{taskId}/commands
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求字段：

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `action` | 是 | `CONFIRM_EXECUTE` / `REPLAN` / `CANCEL` / `RETRY_FAILED` |
| `feedback` | 否 | 调整计划、取消原因等自然语言说明 |
| `version` | 是 | 当前任务版本号，用于乐观锁 |

### 8.1 确认执行

```json
{
  "action": "CONFIRM_EXECUTE",
  "feedback": null,
  "version": 1
}
```

使用条件：

- `planningPhase=PLAN_READY`
- 或 runtime `task.status=WAITING_APPROVAL`
- `actions.canConfirm=true`

成功后：

- 任务进入 `EXECUTING`。
- 后端触发执行链路。
- 前端刷新 `/runtime`。

### 8.2 调整计划

```json
{
  "action": "REPLAN",
  "feedback": "再加一条：最后输出一段可以直接发到群里的项目进展摘要",
  "version": 1
}
```

成功后返回新的 `PlanPreviewVO`。  
前端需要用返回的 `version` 更新本地版本。

### 8.3 取消任务

```json
{
  "action": "CANCEL",
  "feedback": "用户取消",
  "version": 1
}
```

成功后：

- `planningPhase=ABORTED`
- runtime 状态为 `CANCELLED`

### 8.4 重试失败任务

```json
{
  "action": "RETRY_FAILED",
  "feedback": null,
  "version": 3
}
```

使用条件：

- runtime `task.status=FAILED`
- `actions.canRetry=true`

成功后：

- 任务回到 `EXECUTING`。
- 失败或卡住的步骤会重置为 `READY`。
- 对应 step 的 `retryCount + 1`。
- 已有 artifact 不会被删除。
- runtime events 会出现 `STEP_RETRY_SCHEDULED`。

非失败状态调用会返回 `50001`，前端展示 `message` 即可。

### 8.5 version 冲突处理

如果请求里的 `version` 已过期，返回：

```json
{
  "code": 40900,
  "data": null,
  "message": "Version conflict: expected 2, got 1"
}
```

前端处理建议：

1. 不要清空用户输入。
2. 提示“任务已被其他端更新，请刷新后再操作”。
3. 立即重新请求 `/planner/tasks/{taskId}/runtime`。
4. 如用户是在写 replan feedback，保留输入框内容，方便用户复制或重新提交。

## 9. 回答澄清问题

```http
POST /planner/tasks/{taskId}/resume
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "feedback": "主要面向老板汇报，输出文档，语言简洁正式",
  "replanFromRoot": false
}
```

使用条件：

- `planningPhase=ASK_USER`
- 或 runtime `task.status=CLARIFYING`
- `clarificationQuestions` 非空
- `actions.canResume=true`

成功后：

- 后端继续规划。
- 前端刷新 `/runtime` 或继续等 SSE。

## 10. 前端推荐页面流程

### 10.1 任务工作台

1. 登录后调用 `GET /planner/tasks?limit=20`。
2. 进行中 tab 调 `GET /planner/tasks/active`。
3. 待确认 tab 调 `GET /planner/tasks?status=WAITING_APPROVAL`。
4. 失败 tab 调 `GET /planner/tasks?status=FAILED`。
5. 订阅 `GET /planner/tasks/events/stream?activeOnly=true`，收到事件后刷新对应任务。

### 10.2 创建任务

1. 用户输入自然语言。
2. 用户可选中 IM 消息，填入 `workspaceContext.selectedMessages`。
3. 调 `POST /planner/tasks/plan`。
4. 立刻跳转详情页。
5. 详情页先拉 `/runtime`，再订阅单任务 SSE。

### 10.3 任务详情

1. `GET /planner/tasks/{taskId}/runtime` 初始化。
2. 根据 `task.status` 渲染状态。
3. 根据 `steps` 渲染 Stepper。
4. 根据 `artifacts` 渲染产物卡片。
5. 根据 `events` 渲染历史时间线。
6. 根据 `actions` 渲染按钮。
7. 收到 SSE 后重新拉 `/runtime`。

## 11. 状态映射建议

后端状态和 GUI 展示状态建议这样映射：

| 后端 planningPhase | runtime status | GUI 展示 |
| --- | --- | --- |
| `INTAKE` | `PLANNING` | 规划中 |
| `INTENT_READY` | `PLANNING` | 规划中 |
| `ASK_USER` | `CLARIFYING` | 等待用户补充 |
| `PLAN_READY` | `WAITING_APPROVAL` | 计划审查中 |
| `EXECUTING` | `EXECUTING` | 执行中 |
| `COMPLETED` | `COMPLETED` | 已完成 |
| `FAILED` | `FAILED` | 失败 |
| `ABORTED` | `CANCELLED` | 已取消 |

## 12. 当前已实现但需要注意的边界

- GUI 正式链路优先使用 `/planner/tasks/plan`，不要使用 `/plan/sync`。
- 任务详情页以 `/runtime` 为事实源，SSE 只作为刷新触发器。
- 当前 SSE 还不是严格的 `STATE_UPDATE/TEXT_CHUNK` 双事件协议。
- 当前澄清问题主要是文本问题，复杂动态表单后续再扩展。
- 当前 `timeRange/docRefs/attachmentRefs` 字段存在，但自动拉取群聊历史、读取文档、解析附件的内容收集 Agent 还未完整实现。
- 当前确认执行后，文档 URL 通常在文档生成完成后出现在 `artifacts` 中；还不是“确认执行后立即创建空文档并返回 URL”的模式。
- 当前失败重试会保留已有 artifact，但文档 URL 幂等复用还需要后续和 doc agent 链路继续收敛。

