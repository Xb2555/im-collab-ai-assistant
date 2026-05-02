# GUI Planner 接口文档

## 1. 基础约定

- Base URL：本地默认按当前后端启动端口，例如 `http://localhost:8080/api`；也可通过 `APP_SERVER_PORT` 配置。
- 响应统一结构：

```json
{
  "code": 0,
  "data": {},
  "message": "ok"
}
```

- 常见业务码：
  - `0`：成功
  - `40000`：请求参数错误
  - `40100`：未登录
  - `40300`：禁止访问
  - `40400`：任务不存在，或无权访问该任务
  - `40900`：版本冲突，请刷新后重试
  - `50000`：系统异常

- 需要登录的接口必须带：

```http
Authorization: Bearer <accessToken>
```

这里的 `accessToken` 是后端登录接口返回的业务 JWT，不是飞书 `user_access_token`。

## 2. 登录相关接口

### 2.1 获取飞书登录 URL

```http
GET /auth/login-url
```

用于前端拿到飞书授权地址并跳转或嵌入二维码登录。

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

### 2.2 飞书 OAuth 回调换业务 Token

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

### 2.3 查询当前登录用户

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

响应：

```json
{
  "code": 0,
  "data": true,
  "message": "ok"
}
```

## 3. 我的任务列表

### 3.1 查询我的任务

```http
GET /planner/tasks?status=WAITING_APPROVAL,EXECUTING&limit=20&cursor=0
Authorization: Bearer <accessToken>
```

参数：

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `status` | 否 | 按任务状态过滤，支持逗号分隔或重复传参 |
| `limit` | 否 | 默认 `20`，最大 `100` |
| `cursor` | 否 | 下一页游标，当前是 offset 字符串 |

任务状态枚举：

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
        "title": "根据飞书项目协作方案生成技术方案文档和 PPT",
        "goal": "根据飞书项目协作方案生成技术方案文档和 PPT",
        "status": "WAITING_APPROVAL",
        "currentStage": "PLAN_READY",
        "progress": 0,
        "needUserAction": false,
        "riskFlags": ["Mermaid 图语义需结合真实系统信息校验"],
        "createdAt": "2026-04-30T13:53:46.598206Z",
        "updatedAt": "2026-04-30T13:58:50.272690Z"
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

等价于过滤：

```text
PLANNING, CLARIFYING, WAITING_APPROVAL, EXECUTING
```

响应结构同 `GET /planner/tasks`。

## 4. 创建与查看计划

### 4.1 异步创建任务计划

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
    "selectedMessages": ["这里可以放用户选中的 IM 消息"],
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

- 该接口快速返回，后台继续生成计划。
- GUI 创建任务时，后端会从登录态注入 `senderOpenId` 和 `inputSource=GUI`，前端不要传用户身份字段。
- 创建后建议立即进入详情页，先请求 `/runtime`，再订阅 `/events/stream`。

响应：

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

### 4.2 同步创建任务计划

```http
POST /planner/tasks/plan/sync
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求体同 `/planner/tasks/plan`。

说明：

- 用于调试和回归验证。
- 该接口会等待计划生成完成，不建议作为正式 GUI 主链路。

### 4.3 获取任务计划预览

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
    "version": 2,
    "planningPhase": "PLAN_READY",
    "title": "生成技术方案文档、汇报 PPT 和群内摘要",
    "summary": "生成技术方案文档、汇报 PPT 和群内摘要 (3 cards)",
    "cards": [
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

规划阶段枚举：

```text
INTAKE, ASK_USER, INTENT_READY, PLAN_READY, EXECUTING, COMPLETED, FAILED, ABORTED
```

## 5. 任务详情与实时状态

### 5.1 获取任务运行时快照

```http
GET /planner/tasks/{taskId}/runtime
Authorization: Bearer <accessToken>
```

这是任务详情页的主接口，包含主任务、步骤、产物和历史事件。

响应：

```json
{
  "code": 0,
  "data": {
    "task": {
      "taskId": "task-id",
      "version": 3,
      "title": "生成技术方案文档、汇报 PPT 和群内摘要",
      "goal": "根据飞书项目协作方案生成一份技术方案文档...",
      "status": "EXECUTING",
      "currentStage": "EXECUTING",
      "progress": 30,
      "needUserAction": false,
      "riskFlags": [],
      "createdAt": "2026-04-30T13:53:46.598206Z",
      "updatedAt": "2026-04-30T13:58:50.272690Z"
    },
    "steps": [
      {
        "stepId": "card-001",
        "name": "生成技术方案文档（含 Mermaid 架构图）",
        "type": "DOC_CREATE",
        "status": "RUNNING",
        "inputSummary": "产出结构清晰、含可渲染 Mermaid 架构图的技术方案文档",
        "outputSummary": null,
        "progress": 50,
        "retryCount": 0,
        "assignedWorker": "doc-create-worker",
        "startedAt": "2026-04-30T13:59:00Z",
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
        "status": "READY",
        "createdAt": "2026-04-30T14:00:00Z"
      }
    ],
    "events": [
      {
        "eventId": "event-id",
        "version": 2,
        "type": "PLAN_READY",
        "stepId": null,
        "message": "Plan ready",
        "createdAt": "2026-04-30T13:58:50.272690Z"
      }
    ],
    "actions": {
      "canConfirm": false,
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

步骤类型常见值：

```text
DOC_DRAFT, DOC_CREATE, PPT_OUTLINE, PPT_CREATE, WHITEBOARD_CREATE, IM_REPLY, ARCHIVE, SUMMARY
```

事件类型常见值：

```text
INTAKE_ACCEPTED, INTENT_ROUTING, CLARIFICATION_REQUIRED, PLANNING_STARTED,
PLAN_GATE_CHECKING, PLAN_READY, PLAN_ADJUSTED, PLAN_APPROVED,
STEP_READY, STEP_STARTED, STEP_COMPLETED, STEP_FAILED,
STEP_RETRY_SCHEDULED, ARTIFACT_CREATED, TASK_COMPLETED, TASK_FAILED, TASK_CANCELLED
```

### 5.2 获取任务卡片列表

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

### 5.3 订阅单任务事件流

```http
GET /planner/tasks/{taskId}/events/stream
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

说明：

- 当前 SSE 会持续最多 10 分钟。
- 推荐详情页打开时：
  1. 先请求 `/runtime` 初始化页面。
  2. 再订阅 `/events/stream`。
  3. 收到事件后追加时间线，或重新请求 `/runtime` 刷新完整状态。

当前返回内容是事件 JSON 字符串流。每个 EventSource 连接都有独立游标，多个详情页或多个标签页同时订阅同一个任务时不会互相抢事件。例如：

```json
{
  "eventId": "...",
  "taskId": "...",
  "status": "PLAN_READY",
  "version": 2,
  "subtasks": [
    {
      "id": "task-001",
      "type": "DOC",
      "title": "生成项目周报文档",
      "taskType": "WRITE_DOC",
      "status": "pending"
    }
  ],
  "requireInput": null,
  "timestamp": "2026-05-02T02:39:31Z"
}
```

### 5.4 订阅我的任务事件流

```http
GET /planner/tasks/events/stream?activeOnly=true
Authorization: Bearer <accessToken>
Accept: text/event-stream
```

说明：

- 适合任务工作台全局监听，不需要前端逐个任务建立 SSE。
- `activeOnly=true` 默认只推送活跃任务事件：`PLANNING/CLARIFYING/WAITING_APPROVAL/EXECUTING`。
- `activeOnly=false` 可推送当前用户更多任务事件。
- 收到事件后，建议按 `taskId` 刷新对应任务的 `/runtime` 或列表项。

## 6. 任务操作

### 6.1 确认执行

```http
POST /planner/tasks/{taskId}/commands
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "action": "CONFIRM_EXECUTE",
  "feedback": null,
  "version": 0
}
```

说明：

- 当计划处于 `PLAN_READY` 且 `actions.canConfirm=true` 时使用。
- `version` 使用最近一次 `/planner/tasks/{taskId}`、`/runtime` 或任务列表返回的版本号。
- 成功后任务进入 `EXECUTING`，后端会触发 harness/doc agent 执行链路。
- 如果返回 `40900`，说明版本冲突，前端应刷新任务后重试。

### 6.2 调整计划

```http
POST /planner/tasks/{taskId}/commands
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "action": "REPLAN",
  "feedback": "再加一条：最后输出一段可以直接发到群里的项目进展摘要",
  "version": 0
}
```

响应仍为 `PlanPreviewVO`，用于更新计划预览。

### 6.3 取消任务

```http
POST /planner/tasks/{taskId}/commands
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "action": "CANCEL",
  "feedback": "用户取消",
  "version": 0
}
```

成功后任务阶段会变成 `ABORTED`，runtime 状态会投影为取消。

### 6.4 重试失败任务

```http
POST /planner/tasks/{taskId}/commands
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "action": "RETRY_FAILED",
  "feedback": null,
  "version": 3
}
```

说明：

- 仅当任务处于 `FAILED` 且 `actions.canRetry=true` 时使用。
- 重试不会重新规划、不生成新 taskId、不删除已有产物。
- 后端会把失败或卡住的步骤重置为 `READY`，`retryCount + 1`，并重新提交执行链路。
- 成功提交后任务进入 `EXECUTING`，事件中会出现 `STEP_RETRY_SCHEDULED`。
- 非失败态调用会返回操作失败提示；旧 `version` 仍返回 `40900`。

### 6.5 回答澄清问题

```http
POST /planner/tasks/{taskId}/resume
Authorization: Bearer <accessToken>
Content-Type: application/json
```

请求：

```json
{
  "feedback": "主要面向老板汇报，语言简洁一些",
  "replanFromRoot": false
}
```

说明：

- 当 `planningPhase=ASK_USER` 且存在 `clarificationQuestions` 时使用。
- 需要携带登录态，后端会校验 owner。

## 7. 前端推荐接入流程

### 7.1 任务列表页

1. 调 `GET /planner/tasks?limit=20` 展示我的任务。
2. 切换 tab 时按状态过滤：
   - 全部：不传 `status`
   - 进行中：用 `/planner/tasks/active`
   - 待确认：`status=WAITING_APPROVAL`
   - 失败：`status=FAILED`
   - 已完成：`status=COMPLETED`
3. 点击任务进入详情页。

### 7.2 任务详情页

1. 调 `GET /planner/tasks/{taskId}/runtime` 初始化。
2. 用 `task` 渲染顶部概览。
3. 用 `steps` 渲染步骤进度。
4. 用 `artifacts` 渲染文档/PPT/摘要产物。
5. 用 `events` 渲染历史时间线。
6. 订阅 `GET /planner/tasks/{taskId}/events/stream`，收到事件后刷新 runtime。

### 7.3 创建任务页

1. 用户输入自然语言任务。
2. 调 `POST /planner/tasks/plan`。
3. 立即跳转到任务详情页。
4. 详情页显示“正在规划”，等待 runtime 进入：
   - `WAITING_APPROVAL/PLAN_READY`：展示确认执行按钮。
   - `CLARIFYING/ASK_USER`：展示澄清问题输入框。
   - `FAILED`：展示失败原因和重试/修改入口。

## 8. 注意事项

- GUI 与 IM 的任务归属统一使用飞书 `openId`。同一个用户在 IM 创建的任务，GUI 登录同一个 `openId` 后可以看到。
- `/planner/tasks/{taskId}/runtime`、`/{taskId}`、`/{taskId}/cards`、`/{taskId}/events/stream` 都会做 owner 校验，非本人任务返回 `40400`。
- 任务详情页建议以 `/runtime` 为事实源，不要只依赖 `/cards`。
- `/plan/sync` 只用于调试，不建议前端正式使用。
- 列表页可以订阅用户级 SSE：`GET /planner/tasks/events/stream`；详情页继续使用单任务 SSE：`GET /planner/tasks/{taskId}/events/stream`。

## 9. 场景 B 前端需求对齐情况

本节用于对齐前端“场景 B：任务理解与规划（Planner）”需求文档。结论先行：

- 当前后端已经支持 GUI 创建任务、异步规划、计划审查、局部调整、确认执行、取消、任务详情、步骤进度、产物列表、历史事件、登录用户任务隔离和 version 乐观锁。
- 当前后端尚未完整支持语音输入、内容收集子 Agent、群聊空间级任务工作台、统一 GUI SSE 事件包、思考流打字机内容、正式的中断/暂停执行。
- 前端主链路应以 `/planner/tasks/plan`、`/planner/tasks/{taskId}/runtime`、`/planner/tasks/{taskId}/commands` 为准，不要接旧的 `/intent/submit`、`/task/execute`、`/task/replan` 命名。

### 9.1 已实现功能

| 前端需求 | 后端实现情况 | 前端接入建议 |
| --- | --- | --- |
| 自然语言创建任务 | 已实现。`POST /planner/tasks/plan` 接收 `rawInstruction`。 | 创建后立即进入详情页，轮询或订阅事件直到 `PLAN_READY`。 |
| 异步规划，避免长时间阻塞 | 已实现。`/plan` 快速返回 `taskId` 和初始 `version=0`，后台继续规划。 | 不要等待 `/plan` 返回完整计划；详情页通过 `/runtime` 刷新。 |
| 同步调试入口 | 已实现。`POST /planner/tasks/plan/sync` 保留同步规划。 | 仅调试使用，正式 GUI 不建议接。 |
| 计划审查挂起 | 已实现。后端状态为 `PLAN_READY`，runtime 任务状态为 `WAITING_APPROVAL`。 | 前端展示为 `REVIEWING_PLAN`，显示确认执行/调整计划按钮。 |
| 细颗粒度步骤条 | 已实现。`GET /runtime` 返回 `steps[]`，每项包含 `stepId/name/type/status/progress/assignedWorker`。 | Stepper 使用 `runtime.steps`，不要依赖旧 SSE 的 `subtasks`。 |
| 步骤标题由后端生成 | 已实现。`steps[].name` 和 `/cards[].title` 均由 planner 生成。 | 前端直接渲染，不需要按枚举硬编码标题。 |
| 确认执行 | 已实现。`POST /commands`，`action=CONFIRM_EXECUTE`。 | 请求必须携带当前 `version`。 |
| 自然语言调整规划 | 已实现。`POST /commands`，`action=REPLAN`，`feedback` 放用户修改意见。 | 调整后使用返回的 `PlanPreviewVO.version` 更新本地版本。 |
| 取消任务 | 已实现。`POST /commands`，`action=CANCEL`。 | 取消成功后刷新 `/runtime`。 |
| 失败任务重试 | 已实现。`POST /commands`，`action=RETRY_FAILED`。 | 仅在 `FAILED` 且 `actions.canRetry=true` 时展示按钮；成功后刷新 `/runtime`。 |
| 澄清/追问 | 已实现基础能力。规划不足时进入 `ASK_USER`，返回 `clarificationQuestions`。 | 前端展示为 `WAITING_USER`，用 `/resume` 提交回答。 |
| 任务历史状态 | 已实现。`GET /runtime` 返回 `events[]`。 | 详情页时间线用 `events` 渲染。 |
| 任务实时状态 | 已实现。单任务 SSE：`GET /events/stream`；用户级 SSE：`GET /planner/tasks/events/stream`。 | 推荐收到 SSE 后重新拉 `/runtime`，以 runtime 为事实源。 |
| 我的任务列表 | 已实现。`GET /planner/tasks` 按登录用户 `openId` 查询。 | 可展示全部任务卡片。 |
| 活跃任务列表 | 已实现。`GET /planner/tasks/active`。 | 列表页可轮询该接口。 |
| IM 创建任务后 GUI 可见 | 已实现。任务归属统一使用 `ownerOpenId`。 | 只要 GUI 登录 openId 与 IM senderOpenId 一致，就能在列表看到。 |
| owner 权限隔离 | 已实现。`/tasks/{taskId}`、`/runtime`、`/cards`、`/events/stream` 做 owner 校验。 | 非本人任务会返回 `40400`。 |
| version 乐观锁 | 已实现并已真实环境验证。`PlanPreviewVO.version`、`TaskSummaryVO.version`、`runtime.task.version`、`events[].version` 均已返回。 | 调 `/commands` 必须传最近一次拿到的 `version`；`40900` 时刷新任务。 |
| 多端并发冲突检测 | 已实现服务端校验。旧 version 调命令返回 `40900`。 | Axios 拦截 `response.data.code === 40900` 后提示并刷新。 |
| selectedMessages 文本数组 | 已实现。`workspaceContext.selectedMessages: string[]`。 | Cherry-Picking 场景直接传字符串数组。 |
| selectedMessages 优先于 timeRange | 已实现。后端提取上下文时优先使用 `selectedMessages`，其次 `timeRange`。 | 用户手选消息时优先传 `selectedMessages`。 |
| 文档/PPT/摘要常见任务规划 | 已实现。当前稳定支持 DOC/PPT/SUMMARY，统一通过 planner graph 进入规划、审查、门禁和 runtime 投影。 | 常见任务仍应快速进入 `PLAN_READY`，但不再依赖旧规则 fast path。 |

### 9.2 部分实现功能

| 前端需求 | 当前状态 | 缺口/注意事项 |
| --- | --- | --- |
| 状态名 `WAITING_USER` | 后端状态是 `ASK_USER` 或 runtime status `CLARIFYING`。 | 前端需要映射：`ASK_USER/CLARIFYING -> WAITING_USER`。 |
| 状态名 `REVIEWING_PLAN` | 后端状态是 `PLAN_READY` 或 runtime status `WAITING_APPROVAL`。 | 前端需要映射：`PLAN_READY/WAITING_APPROVAL -> REVIEWING_PLAN`。 |
| SSE 事件驱动 UI | 已实现单任务和用户级 SSE。事件包含 `eventId/taskId/status/version/subtasks/requireInput/timestamp`。 | `subtasks` 已补充 `id/type/title`，前端仍建议以 `/runtime` 为事实源刷新完整状态。 |
| `requireInput` 动态表单 | 已实现基础 `TEXT` 澄清输入。ASK_USER 事件会携带 `requireInput.type=TEXT` 与 `prompt`。 | `DATE_RANGE/CONFIRM/CHOICE` 等更丰富表单类型后续按真实场景扩展。 |
| 时间段上下文 | `workspaceContext.timeRange` 字段存在，planner 可读取。 | 还没有真正按 timeRange 自动拉群聊历史的内容收集子 Agent。 |
| 文档引用上下文 | `workspaceContext.docRefs` 字段存在。 | 还没有统一内容收集子 Agent 自动读取文档内容后注入 planner。 |
| 附件上下文 | `workspaceContext.attachmentRefs` 字段存在。 | 还没有自动解析附件内容的工具链。 |
| 复杂任务进入 LLM 深度规划 | 已通过 `PlannerSupervisorGraphRunner` 的 `context_check -> plan/replan/review/gate` 主路径承载。 | 内容收集第一版仍偏摘要/上下文判断，后续需要接真实 IM 历史、文档搜索和会议纪要工具。 |
| “已有文档转 PPT 不生成 DOC” | planner 已能根据用户语义生成 PPT step，但还不是强契约。 | 对 `docRefs + 转 PPT` 这类任务还需要加强规则/gate，确保不误加 DOC step。 |
| IM 与 GUI 双端同步 | 已通过同一 `TaskRecord/Step/Event` 存储实现基础同步，并提供用户级 SSE。 | 用户级 SSE 适合工作台全局监听；详情页仍用单任务 SSE。 |
| 多任务展示 | 已有“我的任务列表”。 | 多任务并行执行调度、队列占用、并发限制展示还未系统化。 |

### 9.3 未实现功能

| 前端需求 | 未实现点 | 建议后端后续实现 |
| --- | --- | --- |
| `/api/intent/submit` | 不计划单独实现旧路径。 | 前端直接使用 `/api/planner/tasks/plan`，该接口已覆盖任务意图提交能力。 |
| `voiceInstruction` | DTO 已接受 `voiceInstruction.format/payload`，但当前不会转写或进入规划。 | 接入 ASR 后再将转写文本合并到 `rawInstruction`。 |
| 内容收集子 Agent | 尚未真正按任务自动调用 IM/doc/file/search tools 拉取资料。 | 新增 `ContextCollectorAgent`，输出 `CollectedContext`，作为 planner 输入。 |
| `COLLECT_CONTEXT_FAILED` | 尚无该错误码和失败分支。 | 内容收集失败时返回标准 code，并提供补救建议。 |
| 群聊空间任务工作台 | 当前按 ownerOpenId 查“我的任务”，没有按 `groupId/chatId` 查空间任务。 | 新增 `GET /planner/chats/{chatId}/tasks`，并设计权限。 |
| 统一用户级 SSE | 已实现 `GET /planner/tasks/events/stream`，推送当前用户全部任务事件。 | 默认 `activeOnly=true`，可传 `activeOnly=false` 看更多任务事件。 |
| GUI 标准 TaskEvent 包 | 已提供基础任务事件包：`eventId/taskId/status/version/subtasks/requireInput/timestamp`。 | 后续如要 `displayStatus/content/createdAt` 等 GUI 专用字段，可再新增 VO；当前不影响工作台实时刷新。 |
| 思考流/打字机 `content` | 当前没有流式思考文本。 | 如果要做打字机体验，需要 planner 阶段输出轻量 content event。 |
| 严格 `selectionType=TIME_RANGE/CHERRY_PICK` | 后端当前 `selectionType` 是自由字符串。 | 增加枚举校验并兼容 `MESSAGE/DOCUMENT/FILE` 旧值。 |
| 动态表单类型 `DATE_RANGE/TEXT/CONFIRM` | 当前未按这个 schema 输出。 | 统一 `RequireInputVO`，让 `ASK_USER` 可驱动动态表单。 |
| 正式中断/暂停执行 | 旧 `/interrupt` 不适合作为 GUI 主链路，未写入本文档。 | 后续如需要，新增鉴权后的 `INTERRUPT_EXECUTION`，并让 harness 支持可中断。 |
| WebSocket 统一废弃声明 | Planner 任务状态主链路已用 SSE/HTTP。 | 如前端仍有 WS 旧代码，需要前后端明确迁移期。 |

### 9.4 推荐状态映射

后端不会为了 GUI 改掉核心状态名，前端建议做展示层映射：

| 后端 planningPhase | runtime status | 前端展示状态 |
| --- | --- | --- |
| `INTAKE` | `PLANNING` | `PLANNING` |
| `INTENT_READY` | `PLANNING` | `PLANNING` |
| `ASK_USER` | `CLARIFYING` | `WAITING_USER` |
| `PLAN_READY` | `WAITING_APPROVAL` | `REVIEWING_PLAN` |
| `EXECUTING` | `EXECUTING` | `EXECUTING` |
| `COMPLETED` | `COMPLETED` | `COMPLETED` |
| `FAILED` | `FAILED` | `FAILED` |
| `ABORTED` | `CANCELLED` | `CANCELLED` |

### 9.5 推荐前端数据源优先级

前端渲染任务详情时，建议按以下优先级取数：

1. `GET /planner/tasks/{taskId}/runtime`
   - 事实源。
   - 用于任务概览、步骤条、产物、历史事件、当前 version。
2. `GET /planner/tasks/{taskId}`
   - 计划预览源。
   - 用于计划审查卡片和澄清问题。
3. `GET /planner/tasks/{taskId}/events/stream`
   - 增量通知源。
   - 收到事件后建议重新拉 `/runtime`。
4. `GET /planner/tasks` / `/planner/tasks/active`
   - 列表源。
   - 用于任务工作台。

### 9.6 当前真实环境已验证点

已在本地真实服务 `8078` 上验证：

- `/planner/tasks/plan` 初始返回 `version=0`。
- 规划完成后 `/planner/tasks/{taskId}` 返回 `version=1`。
- `/planner/tasks/{taskId}/runtime.data.task.version` 返回 `1`。
- `/planner/tasks` 列表项返回 `version=1`。
- 使用 `version=1` 调整计划后返回 `version=2`。
- 再使用旧 `version=1` 调整计划，返回：

```json
{
  "code": 40900,
  "message": "Version conflict: expected 2, got 1"
}
```

因此前端可以放心基于 `version` 做多端乐观锁。
