# Planner 与 IM 交互开发规则

## 1. 文档目的

本文沉淀 planner 与 IM 联动开发中的固定规则，避免后续迭代时反复遗忘已经验证过的产品边界。

核心原则：

1. AI Agent 是主驾驶，GUI 是仪表盘和辅助操作台。
2. IM 是自然协作入口，不是 GUI 卡片和 runtime JSON 的简单转发通道。
3. Planner 负责理解、澄清、规划、调整计划和产出执行契约，不负责把长耗时执行塞进同步请求。
4. 用户随时可以查询任务进度和完整计划，查询本身不能触发重规划。
5. 不要用无限关键词表解决自然语言问题；高风险动作用硬规则，模糊表达用轻量 LLM 兜底。

## 2. 场景边界

本规则主要覆盖：

1. 场景 A：飞书 IM 入口与用户对话。
2. 场景 B：任务理解、规划、计划修改、状态投影。
3. 场景 C/D/F 的接入边界：文档、PPT、摘要等产物通过 runtime/artifact 回流，但 IM 只做自然通知。

禁止做法：

1. 不要让 IM dispatcher 直接调用文档/PPT工具。
2. 不要在 IM 文案里拼接完整 runtime、JSON、Mermaid、长 preview。
3. 不要让 planner 执行真实文档写入或 PPT 创建。
4. 不要为了某个测试输入把核心链路写死成固定 demo。

## 3. 意图识别规则

### 3.1 固定意图枚举

当前支持的用户意图必须收敛到固定枚举：

```text
START_TASK
ANSWER_CLARIFICATION
ADJUST_PLAN
QUERY_STATUS
CONFIRM_ACTION
CANCEL_TASK
UNKNOWN
```

新增自然语言表达时，优先判断能否映射到以上枚举。不要随意新增业务意图，除非已经有完整的执行链路、状态模型和测试。

### 3.2 硬规则只覆盖少量高确定性动作

硬规则优先处理：

1. `CANCEL_TASK`：取消、停止、不用做了。
2. `CONFIRM_ACTION`：开始执行、确认执行、重试、重新执行。
3. 高频 `QUERY_STATUS`：进度、状态、做到哪了。
4. 空输入：`UNKNOWN`。
5. 新会话默认：`START_TASK`。
6. `ASK_USER` 阶段普通回复默认：`ANSWER_CLARIFICATION`。

硬规则不应该维护庞大关键词表。像“老板要的版本也来一份”“顺手补个风险表”“帮我看下这个计划”这类表达，应优先走轻量 LLM 分类。

### 3.3 LLM 只做固定意图选择

轻量 LLM 分类器只允许输出结构化结果：

```json
{
  "intent": "QUERY_STATUS",
  "confidence": 0.9,
  "reason": "user asks for the current plan",
  "normalizedInput": "完整的计划给我看看",
  "needsClarification": false
}
```

约束：

1. LLM 不能生成新意图。
2. LLM 不能直接生成计划步骤。
3. LLM 不能回答用户业务问题。
4. 每轮最多一次 LLM intent 调用。
5. 默认超时应短，建议 2-3 秒。
6. 低置信度、非法 JSON、超时，不能误触发 `ADJUST_PLAN`。

### 3.4 Decision Guard

LLM 结果必须经过本地 guard：

1. 新会话里除取消/未知外，默认修正为 `START_TASK`。
2. `ASK_USER` 阶段优先修正为 `ANSWER_CLARIFICATION`。
3. 已有计划中，LLM 返回 `START_TASK` 要谨慎拒绝，避免误开新任务。
4. `ADJUST_PLAN` 必须要求已有计划可调整。
5. 高风险动作以硬规则为准，LLM 不能覆盖取消或确认执行。

## 4. UNKNOWN 回复规则

如果最终意图是 `UNKNOWN`，不要回复固定模板，例如：

```text
我没完全判断清楚你的意思。你可以直接说要怎么改...
```

正确做法：

1. 由 planner 生成一条上下文相关的自然回复。
2. 回复应短、像真人、不暴露内部路由和枚举。
3. 不要输出 A/B/C 式技术选项。
4. 不要假装完成了动作。
5. 如果用户只是弱认可或轻反馈，例如“这个方案感觉还行”，不要回复“没理解”，应承接并保留当前计划。
6. 模型不可用时才使用本地兜底，本地兜底也必须读取用户原话。

示例：

```text
好，那我先保留当前计划。要继续就回复“开始执行”，要调整也可以直接说。
```

```text
我先保留当前计划。你可以继续补充想改的点；不用改的话回复“开始执行”就行。
```

## 5. 查询计划与查询进度

### 5.1 查询进度

以下表达应返回简短 runtime 状态：

```text
进度怎么样
现在做到哪了
任务概况
当前任务
状态
```

回复内容应包含：

1. 当前任务状态。
2. 正在处理的步骤。
3. 已完成步骤数。
4. 已有产物数量。

不能触发：

1. replan。
2. planning agent。
3. 任务版本增长。
4. 新建任务。

### 5.2 查询完整计划

以下表达应返回完整计划，而不是简版进度：

```text
计划
当前计划
计划是什么
看看计划
给我计划
完整计划
完整的计划给我看看
详细计划
展开计划
所有步骤
full plan
```

IM 返回应使用 `fullPlan(session)`，展示每个 card 的类型、标题和简短描述。

HTTP 侧对应接口：

1. `GET /api/planner/tasks/{taskId}`：完整任务预览。
2. `GET /api/planner/tasks/{taskId}/cards`：计划卡片列表。
3. `GET /api/planner/tasks/{taskId}/runtime`：运行时快照。

## 6. 计划修改规则

### 6.1 默认局部修改

用户说：

```text
再加一条...
顺手补一个...
不要这个步骤
把最后一步改成...
先做文档再做 PPT
```

必须走局部 patch，而不是整包重新规划。

规则：

1. 默认保留未命中的原步骤。
2. 新增步骤只追加或插入相关位置。
3. 删除步骤只删除目标步骤，不影响其他 DOC/PPT/SUMMARY。
4. 更新步骤只更新目标步骤。
5. 重排步骤后要修复依赖。
6. `cardId` 必须重新规范化，不能出现 duplicate stepId。
7. 已完成或已有 artifact 的步骤默认标记 `SUPERSEDED`，不删除产物。

### 6.2 只有明确要求才整包重做

以下表达才允许触发整包重规划：

```text
全部重做
重新规划整个任务
推倒重来
从头按这个目标重新拆
```

否则都应视为局部调整。

### 6.3 模型职责

轻量 replan LLM 只负责把用户话解释成局部 patch 意图：

```text
ADD_STEP
REMOVE_STEP
UPDATE_STEP
REORDER_STEP
CLARIFY_REQUIRED
REGENERATE_ALL
```

本地 `PlanPatchMerger` 负责真正合并、修复 `cardId`、修复依赖、校验 graph。

## 7. IM 文案规则

### 7.1 PLAN_READY

不要把完整 GUI 卡片贴进 IM。默认只回 2-4 个关键步骤：

```text
我准备这样推进：
1. 先生成技术方案文档
2. 再基于文档整理汇报 PPT

没问题的话回复“开始执行”。要改的话直接说，比如“加一段群内摘要”。
```

### 7.2 PLAN_ADJUSTED

只回复变化后的短计划，不重复成功标准、风险列表、runtime JSON：

```text
计划已更新，我会按这个顺序推进：
1. 先生成技术方案文档
2. 再生成配套 PPT
3. 最后生成群内摘要

没问题的话回复“开始执行”。
```

### 7.3 ASK_USER

最多问 1-2 个关键问题：

```text
我还需要确认一下：这份内容主要面向老板汇报，还是技术评审？
```

不要问卷式输出，不要一次抛很多问题。

### 7.4 CONFIRM_ACTION

用户回复“开始执行/确认执行/重试/重新执行”时，应立即回复：

```text
好的，开始执行。我会先处理：生成技术方案文档。完成后继续推进后续步骤。
```

确认执行不应阻塞 IM 等待真实文档创建完成。

### 7.5 执行失败

执行失败必须通知用户，不能只写日志。

正确回复：

```text
任务执行失败了：飞书文档创建超时。

任务状态：失败
当前步骤：生成技术方案文档
步骤进度：0/2

你可以稍后回复“重试”或“重新执行”再试一次。
```

禁止暴露：

1. Java stack trace。
2. `duplicate stepId` 之类内部错误。
3. OpenAPI 原始错误长 JSON。
4. gate/worker 内部类名。

### 7.6 执行完成或中间产物回传

IM 只展示可分享链接和简短进度。

禁止在 IM 中展示：

1. `ArtifactRecord.preview`。
2. 大段 JSON。
3. Mermaid 源码。
4. 文档完整正文。
5. DIAGRAM 这类中间 artifact 的原始内容。

推荐回复：

```text
我检查了一下，当前产物已经生成完成。

产物：
1. 飞书项目协作方案技术设计文档
   https://...

任务状态：已完成
步骤进度：1/3
已有产物：3 个
```

说明：

Mermaid 图可以作为中间 artifact 存在于 runtime/GUI 中，但 IM 不应展开。

## 8. Runtime 与事件规则

Planner 的关键阶段必须持续写入任务状态和事件：

```text
INTAKE_ACCEPTED
INTENT_ROUTING
CLARIFICATION_REQUIRED
PLANNING_STARTED
PLAN_GATE_CHECKING
PLAN_READY
PLAN_ADJUSTED
PLAN_FAILED
EXECUTING
TASK_FAILED
COMPLETED
```

GUI 和 IM 查询状态时必须读取 runtime/session 事实源，不从模型重新生成。

规则：

1. 查询状态不增版本。
2. 查询计划不增版本。
3. 计划修改才增版本。
4. 执行状态变化才写事件。
5. 失败和完成都必须有用户可见通知。

## 9. 异步与耗时规则

### 9.1 Plan 异步化

`/planner/tasks/plan` 应快速接单：

1. 立即返回 `taskId`。
2. 后台完成规划。
3. 前端/IM 通过 `/runtime`、SSE 或后续 IM 回复查看进度。

保留同步调试接口：

```text
POST /planner/tasks/plan/sync
```

### 9.2 模型调用预算

建议默认预算：

1. 意图 LLM：2-3 秒。
2. UNKNOWN 自然回复 LLM：1-2 秒。
3. Replan patch LLM：3-5 秒。
4. 常见 DOC/PPT/SUMMARY 规划优先走 fast path。

禁止：

1. 一轮 replan 多次调用完整 planning agent。
2. structured output 失败后再二次调用同一个长 prompt。
3. 用户只是查进度时调用 planning agent。

## 10. DeepSeek 配置规则

当前项目使用 DeepSeek 官网 API，而不是 DashScope：

```yaml
spring:
  ai:
    model:
      chat: deepseek
    deepseek:
      api-key: ${DEEPSEEK_API_KEY:}
      chat:
        options:
          model: ${DEEPSEEK_MODEL:deepseek-chat}
          temperature: ${DEEPSEEK_TEMPERATURE:0.2}
          max-tokens: ${DEEPSEEK_MAX_TOKENS:4096}
```

启动环境变量：

```bash
export DEEPSEEK_API_KEY=你的DeepSeek官网APIKey
export DEEPSEEK_MODEL=deepseek-chat
```

默认必须使用 `deepseek-chat` 这类非 thinking 模型。当前 planner 的 supervisor agent 会通过
sub-agent tool 调用 clarification agent；`deepseek-reasoner`、`deepseek-v4-flash` 等 thinking
模式模型在 tool-call 多轮链路里需要回传 `reasoning_content`，Spring AI Alibaba 当前链路没有稳定
透传这段字段，容易触发 DeepSeek 400：

```text
The `reasoning_content` in the thinking mode must be passed back to the API.
```

因此：

1. planner / IM / replan 默认不要配置 `deepseek-reasoner` 或 thinking 模型。
2. 需要使用 reasoning 模型时，应单独封装无 tool 的一次性分析链路，不能直接替换全局 `ChatModel`。
3. 轻量意图分类和 replan patch 必须继续使用短延迟模型。

## 11. 真实测试必测用例

### 11.1 主链路

```text
根据飞书项目协作方案生成一份技术方案文档，包含 Mermaid 架构图，再生成一份汇报 PPT 初稿
```

预期：

1. IM 返回短计划。
2. HTTP runtime/cards 有完整结构。
3. 不贴 JSON/Mermaid。

### 11.2 查完整计划

```text
完整的计划给我看看
```

预期：

1. 返回完整计划。
2. 不返回“任务状态：等待你确认”。
3. 不返回 UNKNOWN。

### 11.3 查进度

```text
进度怎么样
```

预期：

1. 返回任务状态。
2. 不触发 replan。
3. 不增加版本。

### 11.4 连续修改

```text
再加一条：最后输出一段可以直接发到群里的项目进展摘要
顺手补一个风险表
我现在不想要群里摘要了
```

预期：

1. 局部修改。
2. 原 DOC/PPT 不丢。
3. 删除摘要后保留风险表。
4. 不出现 duplicate stepId。

### 11.5 执行失败与重试

制造文档创建失败后，应收到失败通知。修复配置后：

```text
重试
```

预期：

1. 被识别为确认执行。
2. 重新触发执行。
3. 不被当作计划修改。

### 11.6 产物通知

执行完成或阶段性回传时：

1. 只显示链接和进度。
2. 不显示 preview。
3. 不显示 Mermaid。
4. 不显示 JSON。

## 12. 开发自检清单

提交前至少检查：

1. 是否新增了硬编码关键词？如果是，是否属于高风险/高频硬规则？
2. `UNKNOWN` 是否能给自然回复？
3. 查询类消息是否不会触发 replan？
4. 计划修改是否只做局部 patch？
5. 是否可能产生 duplicate cardId/stepId？
6. IM 是否展示了 JSON、Mermaid 或长 preview？
7. 失败是否通知用户？
8. runtime/cards/session 是否一致？
9. 是否破坏 B -> C 文档生成链路？
10. 是否跑过相关模块测试？

推荐回归命令：

```bash
mvn -pl im-collab-ai-assistant-planner,im-collab-ai-assistant-imGateWay -am test
mvn -pl im-collab-ai-assistant-app -am test
```

## 13. 下一阶段优化入口

本文档沉淀的是 planner 与 IM 交互开发时必须遵守的边界。非阻塞优化、后续路线图和暂不建议做的事项，统一维护在 [Planner 下一阶段优化建议](planner-next-optimizations.md)。
