# DocAgent 完成态黑盒接入说明

## 一、同事需求 1：现有入口不变，仍通过 Planner `/commands` 进入

> 前端和 IM 不新增入口，完成态产物修改统一走 Planner 命令：
>
> `POST /api/planner/tasks/{taskId}/commands`
>
> Planner 已经负责：
>
> 1. 判断这是完成态任务调整。
> 2. 判断用户是“修改已有产物”还是“保留旧版重新生成”。
> 3. 多个完成任务时让用户先选任务。
> 4. 多个产物时让用户先选产物。
> 5. 根据 `targetArtifactId` 定位目标 artifact。
>
> Doc Agent 不需要处理这些选择和入口分流，只需要提供“修改指定 DOC artifact”的能力。

### 1.1 代码在哪

- Controller 入口：
  - [PlannerController.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/main/java/com/lark/imcollab/app/planner/controller/PlannerController.java)
- GUI 命令入参 DTO：
  - [PlanCommandRequest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/dto/PlanCommandRequest.java)
- 命令应用服务：
  - [PlannerCommandApplicationService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/main/java/com/lark/imcollab/app/planner/service/PlannerCommandApplicationService.java)
- 完成态重规划 / 原地修改决策：
  - [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)
- IM 会话入口：
  - [PlannerConversationService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/service/PlannerConversationService.java)

### 1.2 我做了什么事情

> 原先本地测试用的 `5.1. 执行文档迭代` 和 `5.2. 审批文档迭代计划` 接口，仍保留，防止接入失败导致功能崩盘

- 没有新增新的 GUI / IM 入口。
- GUI 仍然通过 `PlanCommandRequest` 进入 `/planner/tasks/{taskId}/commands`。
- IM 仍然通过 `PlannerConversationService` 进入现有 Planner 对话链路。
- 完成态 DOC 修改的选择逻辑仍在 planner：
  - 是否完成态调整
  - 是否原地修改还是重生成
  - 多任务选择
  - 多产物选择
  - `targetArtifactId` 定位
- DocAgent 新增的黑盒层只承接“已经确定目标 artifact 后，如何改这份 DOC”。

## 二、同事需求 2：通过 common facade 暴露稳定黑盒能力

> 建议通过 common facade 暴露一个稳定黑盒能力，命名可再商量：
>
> ```java
> public interface DocumentArtifactIterationFacade {
>     DocumentArtifactIterationResult edit(DocumentArtifactIterationRequest request);
> }
> ```

### 2.1 代码在哪

- 黑盒 facade：
  - [DocumentArtifactIterationFacade.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/facade/DocumentArtifactIterationFacade.java)
- 请求 DTO：
  - [DocumentArtifactIterationRequest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/dto/DocumentArtifactIterationRequest.java)
- 返回 VO：
  - [DocumentArtifactIterationResult.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/vo/DocumentArtifactIterationResult.java)
- 审批展示 VO：
  - [DocumentArtifactApprovalPayload.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/vo/DocumentArtifactApprovalPayload.java)
- 状态枚举：
  - [DocumentArtifactIterationStatus.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/enums/DocumentArtifactIterationStatus.java)

### 2.2 我做了什么事情

- 已新增稳定黑盒接口 `DocumentArtifactIterationFacade`。
- 当前接口如下：

```java
public interface DocumentArtifactIterationFacade {
    DocumentArtifactIterationResult edit(DocumentArtifactIterationRequest request);

    DocumentArtifactIterationResult decide(
            String iterationTaskId,
            String artifactId,
            String docUrl,
            DocumentIterationApprovalRequest request,
            String operatorOpenId
    );
}
```

- `edit(...)` 对齐同事原始需求。
- 额外补了 `decide(...)`，用于把 `WAITING_APPROVAL` 后续确认链路也收口为同一层黑盒，避免 app/planner 继续泄漏底层文档迭代语义。

## 三、同事需求 3：请求 / 响应字段契约稳定

> 请求字段契约：
>
> `taskId / artifactId / docUrl / instruction / operatorOpenId / workspaceContext`
>
> 响应字段契约：
>
> `taskId / artifactId / docUrl / status / requireInput / summary / preview / modifiedBlocks / approvalPayload`

### 3.1 代码在哪

- 请求 DTO：
  - [DocumentArtifactIterationRequest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/dto/DocumentArtifactIterationRequest.java)
- 返回 VO：
  - [DocumentArtifactIterationResult.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/vo/DocumentArtifactIterationResult.java)
- 审批 payload：
  - [DocumentArtifactApprovalPayload.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/vo/DocumentArtifactApprovalPayload.java)

### 3.2 我做了什么事情

- 已将同事要求的主要请求字段全部落到 `DocumentArtifactIterationRequest`。
- 已将同事要求的主要返回字段全部落到 `DocumentArtifactIterationResult`。
- 返回状态统一收敛为：
  - `COMPLETED`
  - `WAITING_APPROVAL`
  - `FAILED`

## 四、同事需求 4：Planner 的 DOC 分支改为调用这个黑盒 facade

> Planner 当前 DOC 分支暂时是“未接入提示”。Doc Agent 提供 facade 后，把 DOC 分支改成调用：
>
> ```java
> result = documentArtifactIterationFacade.edit(request);
> ```

### 4.1 代码在哪

- 完成态 DOC 修改主入口：
  - [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)

### 4.2 我做了什么事情

- `ReplanNodeService` 已不再直接依赖旧 `DocumentIterationFacade`。
- 当前已改为组装 `DocumentArtifactIterationRequest` 后调用：

```java
documentArtifactIterationFacade.edit(request)
```

- Planner 仍负责上游判断，DocAgent 只负责已定位 artifact 后的实际修改。

## 五、同事需求 5：成功修改时必须保持同 task / 同 artifact / 同 docUrl

> 必须遵守：
>
> 1. 不创建新 `taskId`
> 2. 不创建新 DOC 文档
> 3. 不新增 DOC artifact
> 4. 不改变原 artifact 的 `artifactId`
> 5. 不改变原 artifact 的 `url`

### 5.1 代码在哪

- planner 成功态处理：
  - [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)
- 产物实体：
  - [ArtifactRecord.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/entity/ArtifactRecord.java)
- 黑盒适配实现：
  - [DocumentArtifactIterationFacadeImpl.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/service/DocumentArtifactIterationFacadeImpl.java)

### 5.2 我做了什么事情

- planner 成功态只更新原 `ArtifactRecord`：
  - `status=UPDATED`
  - `preview`
  - `version`
  - `updatedAt`
- 没有新增新 task、没有新增新 DOC、没有新增新 artifact。
- 黑盒返回中的 `artifactId` 和 `docUrl` 会沿用原目标 artifact 信息。

## 六、同事需求 6：支持 WAITING_APPROVAL 契约

> 如果 Doc Agent 认为修改风险较高，需要用户确认编辑计划，返回：
>
> `status=WAITING_APPROVAL`
>
> Planner 收到该结果后会：
>
> 1. 将 session 置为 `ASK_USER`
> 2. 让 `actions.canResume=true`
> 3. 不把 artifact 标记为 `UPDATED`
> 4. 不发 `TASK_COMPLETED`

### 6.1 代码在哪

- 审批上下文实体：
  - [TaskIntakeState.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/entity/TaskIntakeState.java)
- 完成态 DOC 修改状态流转：
  - [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)
- 审批继续流转：
  - [PlannerCommandApplicationService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/main/java/com/lark/imcollab/app/planner/service/PlannerCommandApplicationService.java)

### 6.2 我做了什么事情

- 已支持 `WAITING_APPROVAL`，不是只做 `COMPLETED/FAILED` 两态。
- `TaskIntakeState` 已补全完成态 DOC 审批挂起上下文：
  - `pendingDocumentIterationTaskId`
  - `pendingDocumentArtifactId`
  - `pendingDocumentDocUrl`
  - `pendingDocumentApprovalSummary`
  - `pendingDocumentApprovalMode`
- `ReplanNodeService` 在 `WAITING_APPROVAL` 时会：
  - `session.planningPhase = ASK_USER`
  - 保留原 artifact，不标 `UPDATED`
  - 不发 `TASK_COMPLETED`
- `PlannerCommandApplicationService` 已改为通过黑盒 `decide(...)` 继续处理审批确认 / 拒绝 / 修改意见。

## 七、同事需求 7：失败时不能污染原 artifact

> 修改失败时：
>
> 1. 失败不能报告成成功
> 2. 不要新增 artifact 记录失败结果
> 3. `summary` 要告诉用户失败原因

### 7.1 代码在哪

- 失败态处理：
  - [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)

### 7.2 我做了什么事情

- `FAILED` 时不会更新原 artifact。
- 不会新增失败 artifact。
- 会保留旧产物内容与 URL。
- 会把失败摘要回写到 `TaskIntakeState.assistantReply`，用于直接给用户展示失败原因。

## 八、同事需求 8：DocAgent 只负责原地修改，不处理重新生成

> Doc Agent 只负责“修改原文档”。
>
> 如果用户要新版，Planner 会走另一条链路。

### 8.1 代码在哪

- 完成态调整分流：
  - [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)

### 8.2 我做了什么事情

- 保留了 planner 侧的边界判断：
  - 修改已有产物 -> 走黑盒 `DocumentArtifactIterationFacade`
  - 保留旧版再生成新版 -> 继续走 planner 正常重规划 / 执行链路
- DocAgent 黑盒没有承担“新建新版文档”的职责。

## 九、同事需求 9：旧底层 document-iteration 能力不要被破坏

> 这份文档只说明 Doc Agent 如何接入现有“已完成任务产物修改”链路。

### 9.1 代码在哪

- 旧 facade：
  - [DocumentIterationFacade.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/facade/DocumentIterationFacade.java)
- 旧请求 / 返回：
  - [DocumentIterationRequest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/dto/DocumentIterationRequest.java)
  - [DocumentIterationVO.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-common/src/main/java/com/lark/imcollab/common/model/vo/DocumentIterationVO.java)
- 旧执行服务：
  - [DocumentIterationExecutionService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/service/DocumentIterationExecutionService.java)

### 9.2 我做了什么事情

- 旧低层接口没有删除。
- 旧 `/document-iteration` 与 `/document-iteration/approval` 调试 / 原子能力仍保留。
- 新黑盒 facade 是在其上层补了一层稳定契约，不是替换底层能力。

## 十、从“帮我写一篇揭阳旅游攻略”开始，数据如何流转

这一节说明两条链路：

1. 首次创建任务时，数据如何流转。
2. 任务完成后，再说“把这份文档补充一段风险分析”时，数据如何流转到新的黑盒层。

### 10.1 首次创建任务：用户说“帮我写一篇揭阳旅游攻略”

#### 10.1.1 IM 链路

1. 用户输入自然语言消息。
2. 上下文先被整理为 `WorkspaceContext`。
3. `PlannerConversationService.handlePlanRequest(...)` 接收：
   - `rawInstruction: "帮我写一篇揭阳旅游攻略"`
   - `workspaceContext: WorkspaceContext`
   - `taskId`
   - `userFeedback`
4. `TaskSessionResolver` 解析当前是否已有会话。
5. `TaskIntakeService` 做 intake 判断。
6. `IntentRouterService` 会把输入路由为 `TaskCommand`。
7. planner 会创建或获取 `PlanTaskSession`。
8. 会在 session 中写入：
   - `TaskInputContext`
   - `TaskIntakeState`
   - 后续生成 `PlanBlueprint`
   - 后续生成 `List<UserPlanCard>`
9. 进入 `PlannerSupervisorGraphRunner` 执行规划与产物生成。
10. 运行时侧会沉淀：
   - `TaskRecord`
   - `ArtifactRecord`
   - `TaskRuntimeSnapshot`

#### 10.1.2 这一段用到的主要数据对象

- `WorkspaceContext`
  - 来源：输入侧上下文聚合
  - 用途：透传聊天 / 消息 / 用户 / 选中内容等上下文
- `TaskCommand`
  - 来源：`IntentRouterService`
  - 用途：把自然语言路由成“开始任务 / 调整计划 / 取消 / 确认”等命令语义
- `PlanTaskSession`
  - 来源：planner 主会话实体
  - 用途：整个任务规划与执行会话的主状态容器
- `TaskInputContext`
  - 来源：挂在 `PlanTaskSession` 上
  - 用途：记录 chatId / threadId / senderOpenId / inputSource 等输入元数据
- `TaskIntakeState`
  - 来源：挂在 `PlanTaskSession` 上
  - 用途：记录当前 intake 类型、assistant reply、挂起选择态、挂起审批态等
- `PlanBlueprint`
  - 来源：planner 规划结果
  - 用途：描述计划蓝图
- `UserPlanCard`
  - 来源：planner 规划结果
  - 用途：描述每一步任务卡片
- `TaskRecord`
  - 来源：运行时持久化
  - 用途：任务主记录
- `ArtifactRecord`
  - 来源：运行时持久化
  - 用途：最终 DOC / PPT 等产物记录
- `TaskRuntimeSnapshot`
  - 来源：运行时查询聚合
  - 用途：给 GUI `/runtime` 展示任务 + 产物 + 动作能力

### 10.2 完成后再次修改：用户说“把这份文档补充一段风险分析”

#### 10.2.1 GUI 链路

1. 请求体使用 `PlanCommandRequest`：
   - `action=REPLAN`
   - `feedback`
   - `artifactPolicy`
   - `targetArtifactId`
   - `workspaceContext`
   - `version`
2. `PlannerController` 收到后调用 `PlannerCommandApplicationService.replan(...)`。
3. `PlannerCommandApplicationService` 会把：
   - `feedback`
   - `artifactPolicy`
   - `targetArtifactId`
   拼成新的指令文本。
4. 然后进入 planner graph，落到 `ReplanNodeService.replan(...)`。
5. `ReplanNodeService` 基于 `PlanTaskSession`、`TaskIntakeState`、`ArtifactRecord` 判断：
   - 当前是不是完成态调整
   - 是不是修改已有产物
   - 目标是不是 DOC
6. 若命中完成态 DOC 原地修改，则组装 `DocumentArtifactIterationRequest`。
7. 调用 `DocumentArtifactIterationFacade.edit(request)`。
8. 返回 `DocumentArtifactIterationResult`。
9. planner 根据 `status` 回写：

   - `ArtifactRecord`
   - `TaskIntakeState`
   - `PlanTaskSession.planningPhase`

#### 10.2.3 这一段用到的主要数据对象

- `PlanCommandRequest`：GUI 命令入参 DTO
- `PlanTaskSession`：当前任务会话实体
- `TaskIntakeState`：挂起调整 / 审批状态实体
- `ArtifactRecord`：目标 DOC artifact 实体
- `DocumentArtifactIterationRequest`：黑盒层请求 DTO
- `DocumentArtifactIterationResult`：黑盒层返回 VO

### 10.3 黑盒层内部如何接到底层 Doc iteration

1. `DocumentArtifactIterationFacadeImpl.edit(...)`
2. 内部转调底层 `DocumentIterationExecutionService.execute(...)`
3. 底层返回 `DocumentIterationVO`
4. 适配层把 `DocumentIterationVO` 映射为 `DocumentArtifactIterationResult`

这一段的数据对象关系是：

- 上层看到：
  - `DocumentArtifactIterationRequest`
  - `DocumentArtifactIterationResult`
- 黑盒内部复用：
  - `DocumentIterationRequest`
  - `DocumentIterationVO`

### 10.4 审批继续时如何流转

1. 如果黑盒第一次返回 `WAITING_APPROVAL`
2. planner 会把挂起信息写入 `TaskIntakeState`
3. 用户后续点击确认或补充意见
4. GUI 仍走 `/planner/tasks/{taskId}/commands`
5. `PlannerCommandApplicationService` 识别到当前是挂起的 DOC 审批
6. 调用：

```java
documentArtifactIterationFacade.decide(
    iterationTaskId,
    artifactId,
    docUrl,
    approvalRequest,
    operatorOpenId
)
```

7. 返回新的 `DocumentArtifactIterationResult`
8. app / planner 根据结果继续推进：
   - `COMPLETED`
   - `WAITING_APPROVAL`
   - `FAILED`

## 十一、当前补充测试

### 11.1 代码在哪

- planner 测试：
  - [ReplanNodeServiceTest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/test/java/com/lark/imcollab/planner/supervisor/ReplanNodeServiceTest.java)
- app 测试：
  - [PlannerCommandApplicationServiceTest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/test/java/com/lark/imcollab/app/planner/service/PlannerCommandApplicationServiceTest.java)
- harness 黑盒适配测试：
  - [DocumentArtifactIterationFacadeImplTest.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/test/java/com/lark/imcollab/harness/document/iteration/service/DocumentArtifactIterationFacadeImplTest.java)

### 11.2 我做了什么事情

- 已补齐 planner / app / harness 三层定向测试。
- 已通过验证：
  - `ReplanNodeServiceTest`
  - `PlannerCommandApplicationServiceTest`
  - `DocumentArtifactIterationFacadeImplTest`

## 十二、当前结论

### 12.1 已满足的同事要求

- 入口没变，仍走 Planner。
- Planner 继续负责任务 / 产物选择和重生成分流。
- common 层已有稳定黑盒 facade。
- planner 的完成态 DOC 分支已接到黑盒 facade。
- 支持 `COMPLETED / WAITING_APPROVAL / FAILED`。
- 审批继续流转也已收口到黑盒 facade。
- 原地修改保持同 `taskId / artifactId / docUrl` 语义。
- 旧底层 document iteration 能力仍保留。

### 12.2 当前边界

- 当前这次改造解决的是“对上暴露稳定黑盒契约”。
- 黑盒内部仍然复用现有 `DocumentIterationExecutionService`。
- 如果后续 DocAgent 内部实现要替换，只需要继续满足这层 facade 契约，上层 planner / app 不需要再跟着改。

## 十三、本次测试情况补充

### 13.1 测试任务运行态

本次用于验证的任务运行态如下：

- `taskId`: `1be35437-a357-4e05-9079-30d9b11d94fa`
- `task.status`: `COMPLETED`
- `artifact.type`: `DOC`
- `artifactId`: `f0ae5578-f1a4-4be4-9abe-f37ce5cc4d71`
- `docUrl`: `https://jcneyh7qlo8i.feishu.cn/docx/XoXIdstFRoy10GxaDMocJPCnnHe`

说明：

- 当前任务已经是完成态。
- 当前任务下只有 1 个 DOC artifact。
- 理论上已经满足“完成态已有文档产物，可尝试原地修改”的前置条件。

### 13.2 第一次测试：未带产物策略与目标 artifact

测试请求：

```json
{
  "action": "REPLAN",
  "feedback": "一、学校概况后补充1.1 校园特色",
  "version": 3
}
```

实际返回：

```json
{
  "code": 0,
  "data": {
    "taskId": "1be35437-a357-4e05-9079-30d9b11d94fa",
    "version": 3,
    "planningPhase": "COMPLETED",
    "clarificationQuestions": [
      "请问您希望介绍广东工业大学的哪些方面？例如学校概况、历史沿革、院系设置、学科优势、校园文化等。另外，是否需要我基于公开知识生成一份通用介绍，还是您有特定的材料或数据需要整理？"
    ],
    "assistantReply": "我先不动当前计划。你想看细节、调整步骤，还是推进执行？"
  },
  "message": "ok"
}
```

测试结论：

- 这次没有命中“完成态 DOC 原地修改”分支。
- 请求被当成了普通 `REPLAN / PLAN_ADJUSTMENT` 语义，没有进入文档迭代执行。

### 13.3 第二次测试：带上 `artifactPolicy` 和 `targetArtifactId`

测试请求：

```json
{
  "action": "REPLAN",
  "feedback": "修改已有文档：在“一、学校概况”后补充“1.1 校园特色”",
  "artifactPolicy": "EDIT_EXISTING",
  "targetArtifactId": "f0ae5578-f1a4-4be4-9abe-f37ce5cc4d71",
  "version": 3
}
```

实际返回：

```json
{
  "code": 40100,
  "data": null,
  "message": "operatorOpenId must be provided"
}
```

测试结论：

- 这次已经不再是“没命中文档修改场景”的问题。
- 请求已经推进到了文档迭代执行链路。
- 但在执行前的文档所有权校验阶段失败，原因是操作者身份缺失。

### 13.4 本次测试暴露出的真实问题

根据当前代码，问题不是前端“不能传 artifactId”，而是 `REPLAN -> 完成态 DOC 修改` 链路里的操作者身份透传不完整。

#### 问题 1：`/commands` 的 `REPLAN` 分支没有透传 `workspaceContext`

当前 `PlannerController` 在处理：

```http
POST /planner/tasks/{taskId}/commands
```

的 `REPLAN` 分支时，调用的是：

```java
plannerCommandApplicationService.replan(
    taskId,
    request.getFeedback(),
    request.getArtifactPolicy(),
    request.getTargetArtifactId()
)
```

也就是说：

- `PlanCommandRequest` 虽然支持 `workspaceContext`
- 但 `REPLAN` 分支当前没有把 `request.getWorkspaceContext()` 往下传
- 所以即使前端补了 `senderOpenId`，当前这条链路也用不上

#### 问题 2：黑盒适配层 `edit(...)` 没有透传 `operatorOpenId`

当前 `DocumentArtifactIterationFacadeImpl.edit(...)` 内部只透传了：

- `taskId`
- `docUrl`
- `instruction`
- `workspaceContext`

没有把 `DocumentArtifactIterationRequest.operatorOpenId` 显式透传到底层执行请求。

因此：

- 上层即使拿到了 `operatorOpenId`
- 黑盒适配层当前也没有完整利用这份数据

#### 问题 3：底层文档所有权校验强依赖操作者身份

底层 `DocumentOwnershipGuard` 在进入编辑前会校验：

- `operatorOpenId` 不能为空

当前报错：

```text
operatorOpenId must be provided
```

就是在这里抛出的。

### 13.5 当前对 `/api/planner/tasks/{taskId}/commands` 的判断

当前可以明确得到的结论是：

1. `/api/planner/tasks/{taskId}/commands` 不是默认重新生成文档。
2. 当请求包含：
   - `artifactPolicy=EDIT_EXISTING`
   - `targetArtifactId`
   - 明确的“修改已有文档”语义
   时，链路已经可以推进到完成态 DOC 修改分支。
3. 当前阻塞真正执行的原因，不是路由没接上，而是操作者身份透传缺口。

### 13.6 当前修复建议

如果要让这条链路在 GUI 场景下真正可用，至少需要补以下两处：

1. `PlannerController` / `PlannerCommandApplicationService`

要求：

- `REPLAN` 分支支持把 `PlanCommandRequest.workspaceContext` 往下透传到 replan 链路

2. `DocumentArtifactIterationFacadeImpl.edit(...)`

要求：

- 显式把 `DocumentArtifactIterationRequest.operatorOpenId` 透传到底层执行链路

### 13.7 本次测试结论摘要

本次测试已经说明：

- 新黑盒 facade 已经接入完成态 DOC 修改主链路。
- `/commands` 也已经可以路由到“修改已有文档”场景。
- 当前剩余问题是“身份透传未打通”，不是“接口能力不存在”。
