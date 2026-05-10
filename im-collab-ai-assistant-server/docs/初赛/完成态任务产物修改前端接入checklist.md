# 完成态任务产物修改前端接入 Checklist

本文给 GUI 联调同学快速说明：应该调哪个接口、传什么参数、预期看见什么结果。

## 1. 先判断任务能不能调

接口：

```http
GET /api/planner/tasks/{taskId}/runtime
```

重点看：

```json
{
  "data": {
    "task": {
      "status": "COMPLETED"
    },
    "actions": {
      "canReplan": true
    }
  }
}
```

预期结果：

1. `task.status=COMPLETED`
2. `actions.canReplan=true`
3. `artifacts` 里能拿到 `artifactId`、`type`、`url`

如果这三个条件不满足，就不要发完成态产物修改命令。

## 2. 修改已有 PPT / DOC

统一接口：

```http
POST /api/planner/tasks/{taskId}/commands
Content-Type: application/json
```

请求体模板：

```json
{
  "action": "REPLAN",
  "feedback": "把第1页标题改成采购风险与建议",
  "artifactPolicy": "EDIT_EXISTING",
  "targetArtifactId": "artifact-id",
  "version": 3
}
```

参数怎么传：

1. `action` 固定传 `REPLAN`
2. `artifactPolicy` 改已有产物时传 `EDIT_EXISTING`
3. `targetArtifactId` 传用户选中的那个产物 ID
4. `version` 传当前任务最新版本
5. `feedback` 必须是完整自然语言

`feedback` 示例：

1. PPT: `把第2页标题改成采购风险与建议`
2. DOC: `在 1.2 后补充一段风险分析`

预期结果分三类。

### A. 直接成功

返回大致会是：

```json
{
  "data": {
    "planningPhase": "COMPLETED",
    "assistantReply": "已补充风险分析章节",
    "actions": {
      "canReplan": true
    }
  }
}
```

你应该看到：

1. `planningPhase=COMPLETED`
2. `assistantReply` 是成功摘要
3. 再查 `/runtime` 后，原 artifact 的 `status=UPDATED`
4. `artifactId` 和 `url` 不变

### B. 进入待确认文档修改计划

这通常发生在 DOC 高风险修改。

返回大致会是：

```json
{
  "data": {
    "planningPhase": "ASK_USER",
    "assistantReply": "已生成待确认的文档修改计划",
    "actions": {
      "canConfirm": true,
      "canReplan": true,
      "canResume": true
    }
  }
}
```

你应该看到：

1. `planningPhase=ASK_USER`
2. `actions.canConfirm=true`
3. `actions.canResume=true`

这不是普通澄清，而是“文档修改待确认态”。

### C. 进入普通追问 / 多产物选择

返回大致会是：

```json
{
  "data": {
    "planningPhase": "ASK_USER",
    "assistantReply": "这个任务下有多个可修改产物，你想修改哪一个？",
    "actions": {
      "canResume": true
    }
  }
}
```

你应该看到：

1. `planningPhase=ASK_USER`
2. 只有 `canResume=true`
3. 没有 `canConfirm=true`

这时按普通补充输入处理即可。

## 3. 确认执行当前 DOC 修改计划

当上一步返回：

1. `planningPhase=ASK_USER`
2. `canConfirm=true`
3. `canResume=true`

说明当前是“待确认文档修改计划”。

用户点“确认执行”时调：

```http
POST /api/planner/tasks/{taskId}/commands
```

请求体：

```json
{
  "action": "CONFIRM_EXECUTE",
  "version": 4
}
```

预期结果：

1. 返回 `planningPhase=COMPLETED`
2. `assistantReply` 变成“文档修改已执行”之类的成功摘要
3. 再查 `/runtime`，原 DOC artifact 的 `status=UPDATED`

## 4. 修改待确认的 DOC 方案

如果用户不满意当前文档修改方案，但还想继续改，用：

```http
POST /api/planner/tasks/{taskId}/commands
```

请求体：

```json
{
  "action": "RESUME",
  "feedback": "请把风险分析改成整章重写，并保留原有结论",
  "version": 4
}
```

预期结果有两种：

1. 再次返回 `ASK_USER + canConfirm=true + canResume=true`
说明后端按新意见重建了待确认方案

2. 返回 `COMPLETED`
说明后端已直接执行完成

## 5. 取消本次 DOC 修改

如果用户决定这次先不改，不是取消整个任务，而是拒绝这次文档修改：

```http
POST /api/planner/tasks/{taskId}/commands
```

请求体：

```json
{
  "action": "RESUME",
  "feedback": "这次先不用改了",
  "version": 4
}
```

预期结果：

1. 返回 `planningPhase=COMPLETED`
2. `assistantReply` 表示本次修改已取消
3. 原 DOC 不发生变化

## 6. 保留旧版并重新生成

如果不是改旧产物，而是保留旧版再生成新的一版：

```http
POST /api/planner/tasks/{taskId}/commands
```

请求体：

```json
{
  "action": "REPLAN",
  "artifactPolicy": "KEEP_EXISTING_CREATE_NEW",
  "feedback": "保留现有文档，重新生成一份新版文档，并补充风险分析章节",
  "version": 3
}
```

预期结果：

1. 返回 `planningPhase=PLAN_READY`
2. `actions.canConfirm=true`
3. 用户再点确认时，再发 `CONFIRM_EXECUTE`
4. 执行完成后 `/runtime` 里会多一个新 artifact

## 7. 联调时最关键的判断规则

1. 修改已有产物：`REPLAN + EDIT_EXISTING + targetArtifactId`
2. 保留旧版生成新的一版：`REPLAN + KEEP_EXISTING_CREATE_NEW`
3. DOC 待确认计划：看 `ASK_USER + canConfirm=true + canResume=true`
4. 普通追问 / 选产物：看 `ASK_USER + canResume=true`，且没有 `canConfirm=true`
5. 原地修改成功后，不会新增 artifact，只会更新原 artifact 的 `status=UPDATED` 和 `preview`
