# 0505 DocAgent 完成态接入交接说明

## 1. 当前目标

当前要做的不是继续修 `document-iteration` 内核，而是把 **DocAgent 文档修改能力接入完成态任务产物调整链路**，进入“场景汇合 / 最终产品组装阶段”。

目标问题：

1. 已完成任务在 `REPLAN` 时，当前只支持 `PPT` 原地修改。
2. `DOC` 产物原地修改仍被拦截，只会提示“暂未接入”。
3. 现在要把 DocAgent 接到这条完成态链路里。

---

## 2. 已确认事实

### 2.1 `document-iteration` 内核现状

文档编辑能力已有独立入口：

- `POST /api/planner/tasks/document-iteration`
- `POST /api/planner/tasks/{taskId}/document-iteration/approval`

核心执行服务：

- [DefaultDocumentIterationExecutionService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/service/DefaultDocumentIterationExecutionService.java)

当前这条链路已经能做：

- 文本修改
- 图片插入
- 表格插入
- before/after section 类结构调整

但这里是独立 DocAgent 通道，还 **没有接入完成态 REPLAN 产品链路**。

### 2.2 完成态 REPLAN 现状

完成态产物调整主干在：

- [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)

其中：

1. `replanCompletedTask(...)` 负责分流
2. `editExistingArtifact(...)` 负责按产物类型进入原地修改
3. 当前只支持 `ArtifactTypeEnum.PPT`
4. `ArtifactTypeEnum.DOC` 当前会直接返回：
   - “Doc 原地编辑能力暂未接入……”

### 2.3 前端说明文档

已有完成态接口说明：

- [完成态任务产物修改接口说明.md](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/docs/完成态任务产物修改接口说明.md)

这份文档里当前仍写着：

- 只支持 PPT 原地修改
- DOC 原地修改暂未接入

后续如果功能接通，这份说明也需要同步更新。

---

## 3. 本会话已经处理过的 document-iteration 相关问题

这部分不是下一步主任务，但属于必须继承的背景。

### 3.1 图片插入误报失败

现象：

- 图片实际已经插入飞书文档
- 但后端报错：
  - `目标状态校验失败：未发现新的 image 节点`

原因：

1. `lark-cli 1.0.23` 图片插入后常常不返回 `newBlocks`
2. `after` 快照主要基于 outline，无法可靠看到 image block
3. 严格 verifier 因此误判失败

已做处理：

- `RichContentTargetStateVerifier` 已加入宽松兜底
- 对图片插入场景，只要 revision 推进且执行成功，不再因为未扫描到 image node 直接报错

### 3.2 前插章节失败

用户场景：

- `在1.2前新增1.0 地理资源概述`

根因：

1. 飞书当前能力没有真正的 `block_insert_before`
2. 前插只能走两步模拟：
   - `append`
   - `block_move_after`
3. `append` 在 `lark-cli 1.0.23` 下经常不返回 `newBlocks`
4. 需要靠 outline/section 恢复新建 block group
5. 原先 `block_move_after` 参数适配错了
6. 原先 before-section verifier 又错误依赖 `topLevelSequence`

已做处理：

1. `DocumentPatchExecutor`
   - append 后新增 runtime group 恢复逻辑
   - 从 outline + section 恢复 appended block ids

2. `LarkDocWriteGateway`
   - `block_move_after` 参数按 `lark-cli 1.0.23` 的 `--api-version v2 --help` 修正
   - 正确映射为：
     - `--src-block-ids` = 被移动 block
     - `--block-id` = 目标 block

3. `DocumentTargetStateVerifier`
   - before-section 校验改为按 `orderedBlockIds + headingIndex` 判断 heading 顺序
   - 不再错误使用只含文档根标题的 `topLevelSequence`

结论：

- 前插失败的主因不是“写入失败”，而是平台不原生支持前插，必须走“追加 + 搬移 + 校验”的高风险受控链路
- 后插之所以稳，是因为后插可直接映射为 `block_insert_after`
- 前插需要审核，后插通常不需要，本质是风险等级差异，不是产品偏好

### 3.3 当前环境事实

必须记住：

- `lark-cli version 1.0.23`

这很关键，尤其是 `docs +update --api-version v2` 参数形态必须以本机真实帮助为准，不要凭记忆假设。

---

## 4. 下一步真正要做的事情

### 4.1 任务本质

把 **DocAgent 的文档原地修改能力** 接入 **完成态 REPLAN 的产物编辑链路**。

换句话说，要让这条路径成立：

1. 完成态任务有 DOC artifact
2. 用户发 `REPLAN`
3. 指令表达的是“修改现有文档”
4. 后端不再提示“Doc 原地编辑能力暂未接入”
5. 而是实际调用 DocAgent 进入文档修改

### 4.2 优先级判断

当前最合理的实施顺序：

1. 先打通“完成态 DOC 原地修改入口”
2. 再决定是否做完整审批闭环

不要一开始就把所有交互态一次做满。

---

## 5. 代码落点

### 5.1 必改主文件

#### A. 完成态 REPLAN 分流

- [ReplanNodeService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/supervisor/ReplanNodeService.java)

要改的地方：

1. 注入 Doc 迭代入口
2. 在 `editExistingArtifact(...)` 里放开 `ArtifactTypeEnum.DOC`
3. 新增 `editExistingDoc(...)`

#### B. GUI commands 入口

- [PlannerCommandApplicationService.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/main/java/com/lark/imcollab/app/planner/service/PlannerCommandApplicationService.java)

当前这里会把：

- `artifactPolicy`
- `targetArtifactId`

拼进 feedback。

通常不用大改，但如果后续要增强 DOC 产物策略 hint，这里可能需要补充。

#### C. 前端视图装配

- [PlannerViewAssembler.java](/abs/path/C:/Users/14527/Desktop/lark-ai/project/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/main/java/com/lark/imcollab/app/planner/assembler/PlannerViewAssembler.java)

如果 Doc 完成态修改会返回：

- `COMPLETED`
- `ASK_USER`
- `WAITING_APPROVAL`

则这里要确认动作位和文案展示是否符合产品要求。

### 5.2 可能新增的 abstraction

当前 planner 里直接依赖：

- `PresentationIterationFacade`

Doc 接入有两种选法：

#### 方案 A：快接

planner 直接注入：

- `DocumentIterationExecutionService`

优点：

- 改动小

缺点：

- planner 直接依赖 harness service，层次不够干净

#### 方案 B：更合理

在 common 增加一个 facade，例如：

- `DocumentIterationFacade`

由 harness/app 层提供实现，planner 只依赖 facade。

优点：

- 和 `PresentationIterationFacade` 风格一致

缺点：

- 代码改动面更大

建议：

- 如果目标是先尽快进入产品组装联调，先用方案 A 或做一个很薄的 facade

---

## 6. 推荐的最小可落地方案

### 6.1 先做什么

先做“完成态下可以对 DOC 发起修改，并返回合理结果”，不要一开始就追求审批二段闭环。

#### 实施方式

在 `ReplanNodeService` 增加：

1. `editExistingDoc(...)`
2. 用 `ArtifactRecord.url` 组装 `DocumentIterationRequest`
3. 调用 DocAgent 执行

### 6.2 返回态如何先简化

DocAgent 可能返回：

1. `COMPLETED`
2. `ASK_USER`
3. `WAITING_APPROVAL`

最小方案建议：

#### `COMPLETED`

- 更新 artifact preview / status
- session 保持 `COMPLETED`
- `assistantReply` 写成文档修改结果摘要

#### `ASK_USER`

- 把 session 置为 `ASK_USER`
- `TaskIntakeState.pendingAdjustmentInstruction` 保留原指令
- `assistantReply` 用 DocAgent 的追问文案

#### `WAITING_APPROVAL`

第一版建议不要接 document-iteration 的独立 approval 接口闭环。

先把它也映射成：

- planner session `ASK_USER`
- `assistantReply` 展示“需要确认执行”的说明

也就是：

- 产品上先做到“不会假成功，不会说未接入”
- 后面再补完成态审批确认的正式闭环

---

## 7. 如果要做完整版闭环，还差什么

如果产品要求这轮就把完成态 DOC 修改完整闭环做完，还需要额外处理：

1. 完成态任务如何关联 `document-iteration` pending taskId
2. GUI 如何在 `/commands` 体系下继续确认 Doc 修改审批
3. `document-iteration/approval` 如何与 planner completed task 的 session 状态互通
4. runtime event / artifact update / planningPhase 的统一投影

这部分工作量明显更大，不建议和“先接通入口”混在一个最小交付里。

---

## 8. 本轮建议实施清单

建议按下面顺序推进：

1. 先确认同事说的“最终产品组装阶段”到底要：
   - 仅接通完成态 DOC 修改入口
   - 还是要连审批确认闭环一起交付

2. 如果先做最小版：
   - 改 `ReplanNodeService`
   - 接入 DocAgent
   - 处理 `COMPLETED / ASK_USER / WAITING_APPROVAL` 三类返回
   - 补测试

3. 联调通过后，再决定是否补：
   - 完成态 DOC 审批确认
   - 前端说明文档更新

---

## 9. 需要重点关注的测试

重点补这些测试：

### planner

- `ReplanNodeServiceTest`
  - completed task + DOC artifact + clear edit instruction -> 调用 DocAgent
  - 返回 `COMPLETED` -> session completed, artifact updated
  - 返回 `ASK_USER` -> session ask_user, pendingAdjustmentInstruction 保留
  - 返回 `WAITING_APPROVAL` -> 当前最小方案下映射为可继续交互的挂起态

### app

- `PlannerCommandApplicationServiceTest`
  - `REPLAN + artifactPolicy + targetArtifactId` 传递不回退

### view

- `PlannerViewAssemblerTest`
  - 完成态 DOC 修改进入挂起态时，actions / assistantReply 符合预期

---

## 10. 不要再重复踩的坑

1. 不要再把 `topLevelSequence` 当成所有章节顺序
   - 对真实飞书文档，它经常只含根 `h1`

2. 不要假设 `lark-cli` 参数
   - 先看本机真实 help
   - 当前环境是 `1.0.23`

3. 不要在这轮继续深挖 `document-iteration` 内核
   - 本轮重点是“完成态场景汇合”
   - 不是继续改 patch / verifier / anchor 内核

4. 不要一上来就做完整审批闭环
   - 先接通入口，再视产品要求补二段确认

---

## 11. 进入新窗口后可直接复述的任务描述

可以直接这样开题：

> 当前仓库里 DocAgent 的 `document-iteration` 已经能独立改飞书文档，但完成态 `REPLAN` 还只支持 PPT 原地修改，DOC 会被 `ReplanNodeService` 直接拦截。现在要做“场景汇合 / 最终产品组装”：把 DOC 原地修改接入完成态产物调整链路，优先实现最小可用版，即 completed task 的 DOC artifact 能进入 DocAgent，正确处理 `COMPLETED / ASK_USER / WAITING_APPROVAL` 三类返回，不先强求完整审批闭环。

