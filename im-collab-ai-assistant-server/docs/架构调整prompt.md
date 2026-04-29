# IM Collab AI Assistant 架构调整 Prompt

你现在要接手一个已经跑起来、但架构边界混乱的 Java 多模块项目。你的任务不是做局部修补，而是**基于现有代码和既定目标架构，对后端模块做一次有边界、有阶段、有验收标准的架构收敛与代码调整**。

请你严格按照下面的背景、目标、约束和交付要求执行。

---

## 1. 项目背景

当前项目路径：

`im-collab-ai-assistant-server`

这是一个以 IM 为入口的办公协同智能助手后端，目标产品说明见：

- `docs/项目功能文档.md`

使用的框架文档见（文档并不完整，必须时要去文档提供的url寻找完整的功能文档）：
- `docs/spring ai alibaba.md`'

目标架构设计说明见：

- `docs/im_harness架构设计.md`

你必须先阅读这两份文档，再阅读代码，再开始改造。

---

## 2. 当前模块现状

当前 Maven 多模块如下：

1. `im-collab-ai-assistant-app`
   - 后端启动模块
   - 目前还包含 planner controller 等入口暴露

2. `im-collab-ai-assistant-imGateWay`
   - 负责飞书 IM 订阅、消息监听、OAuth、聊天相关接口
   - 接收用户原始消息
   - 当前更像“飞书接入模块 + 一部分业务入口”

3. `im-collab-ai-assistant-planner`
   - 当前负责意图理解、规划、任务卡片生成
   - 同时承载了大量 prompt 模板、supervisor 逻辑、计划质量、结果评审等职责
   - 有明显“总控脑过重”的问题

4. `im-collab-ai-assistant-harness`
   - 当前负责执行任务
   - 已有文档和演示两条工作流雏形
   - 但整体仍然偏“固定顺序流水线”

5. `im-collab-ai-assistant-skills`
   - 封装飞书文档、飞书消息等工具调用
   - 本质上是外部能力访问层

6. `im-collab-ai-assistant-store`
   - 当前主要封装 Redis 和 checkpoint
   - 目前偏 planner 状态存储，尚未真正成为统一任务状态底座

7. `im-collab-ai-assistant-common`
   - 存放 facade、dto、entity、enum、异常等公共代码
   - 目前已混入不少“偏业务语义但命名为 common”的内容

---

## 3. 当前架构存在的核心问题

请你带着下面这些判断去审视代码，并据此重构：

1. **双总控问题**
   - `planner` 中的 supervisor / planning / result-judge / advice 等职责很重
   - `harness` 中又有执行总控
   - 最终形成两个“大脑”，边界不清

2. **固定流程问题**
   - `harness` 目前更像按固定步骤跑文档和演示流程
   - 这不符合目标产品“场景可独立成立、也可自由组合编排”的要求

3. **任务状态模型缺失**
   - 当前虽然有 plan card、task event 等对象，但还没有真正形成统一的
     - Task
     - Step
     - Artifact
     - Approval
     - Event
     这一套任务中枢模型

4. **前端卡片与后端状态没有完全打通**
   - 任务卡片更像 planner 产物
   - 但正确做法应该是：卡片是任务状态的投影，不是单独生成物

5. **质量门禁设计不合理**
   - 当前有结果评分、建议等逻辑
   - 但不应继续强化为“统一评分后整轮重跑”
   - 应转向分阶段 gate：Plan Gate / Content Gate / Action Gate / Publish Gate

6. **多端同步层不独立**
   - 题目要求移动端和桌面端实时双向同步
   - 当前后端模块中还没有足够清晰的“事件流 + 状态同步”中心

7. **common 模块边界漂移**
   - `common` 里已经有 `ExecutionHarnessFacade`、`PlannerRuntimeFacade`、`AgentTaskPlanCard` 等强业务内容
   - 需要清理哪些属于真正跨模块契约，哪些属于 planner/harness 内部模型

---

## 4. 目标架构

你必须以 `im_harness架构设计.md` 为准，把现有后端逐步收敛到下面这个架构：

**事件驱动的任务状态机 + 可重规划的编排器 + 能力型工作节点 + 分层质量门禁 + 独立多端同步层**

核心分层如下：

1. 交互入口层
2. 会话与任务中枢层
3. Planner / Orchestrator 层
4. 能力执行层
5. 质量与门禁层
6. 状态同步与协同层

### 4.1 目标职责收敛

请按下面的思路调整模块定位：

#### `im-collab-ai-assistant-app`

只做应用装配和启动：

1. Spring Boot 启动
2. 全局配置装配
3. 各模块 Bean 装配
4. 不承载具体业务编排逻辑

#### `im-collab-ai-assistant-imGateWay`

只做入口接入和外部消息适配：

1. 飞书 IM 订阅与监听
2. OAuth 与用户身份接入
3. 外部消息标准化
4. 将入口消息转成统一 `ConversationInput` / `TaskCommand`
5. 不直接承担 planner/harness 编排细节

#### `im-collab-ai-assistant-planner`

收敛为单一大脑层的一部分，负责：

1. Intent Router
2. Task Planner
3. Replanner
4. Plan Gate
5. Planner 上下文压缩

明确不要继续堆：

1. 结果评分驱动的整轮重跑模型
2. 过度人格化的 supervisor 流程
3. 前端展示对象与内部规划对象混杂

#### `im-collab-ai-assistant-harness`

收敛为执行编排层和工作流层，负责：

1. Execution Orchestrator
2. Step dispatch
3. 将 Agent/Tool 作为 Node 纳入工作流
4. 局部重试与局部回退
5. Content Gate / Action Gate / Publish Gate 的执行协同

不要继续保留“固定顺序主流水线”思维。

#### `im-collab-ai-assistant-skills`

收敛为工具层：

1. 飞书文档工具
2. 飞书消息工具
3. 飞书幻灯片/白板工具（若暂未实现，也要预留明确接口）
4. 工具调用结果和异常标准化

原则：

1. 能工具化的不要人格化成 Agent
2. 外部系统交互必须有显式输入输出

#### `im-collab-ai-assistant-store`

收敛为状态与检查点基础设施层：

1. Task / Step / Artifact / Approval / Event 持久化接口
2. Workflow checkpoint
3. Redis 作为状态和事件存储
4. 为中断恢复、人工介入、跨端同步提供底座

不要只服务 `planner state`。

#### `im-collab-ai-assistant-common`

收敛为真正的跨模块契约和基础组件：

1. 通用异常
2. 基础响应结构
3. 领域无关工具类
4. 跨模块必须共享的领域契约接口与标准模型

不要把 planner/harness 的内部实现细节继续堆进 common。

---

## 5. 必须建立的核心领域模型

请你在重构中建立或显式收敛以下模型，不要求一次性做到 100% 完美，但必须形成清晰骨架：

1. `Conversation`
   - 会话上下文
   - 原始消息、消息来源、用户信息、会话标识

2. `Task`
   - 一次用户目标
   - 对应前端大卡片

3. `Step`
   - 任务下的执行步骤
   - 对应前端小卡片

4. `Artifact`
   - 文档草稿、飞书文档链接、PPT 链接、白板链接、中间提纲等产物

5. `Approval`
   - 待确认动作、用户批准/拒绝/修改

6. `Event`
   - 任务创建、步骤启动、步骤完成、失败、重试、待确认、完成等事件

要求：

1. 前端卡片要以 Task/Step/Event 投影出来，而不是由 planner 单独生成一套与任务状态脱节的结构。
2. 现有 `AgentTaskPlanCard`、`PlanCardsOutput`、`TaskEvent`、`PlanTaskSession` 等对象，请你审视后决定是保留映射、替换、还是下沉为兼容层。

---

## 6. 对 Spring AI Alibaba 的使用要求

你必须优先参考官方文档，不要通过反编译源码来猜框架行为。下面这些官方文档是本次调整的主要依据：

- Agents: `https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents/`
- Human-in-the-Loop: `https://java2ai.com/docs/frameworks/agent-framework/advanced/human-in-the-loop/`
- Multi-agent: `https://java2ai.com/docs/frameworks/agent-framework/advanced/multi-agent/`
- Agent Tool: `https://java2ai.com/docs/frameworks/agent-framework/advanced/agent-tool/`
- Workflow: `https://java2ai.com/docs/frameworks/agent-framework/advanced/workflow/`

你在实现时必须遵守以下框架使用原则：

1. `ReactAgent` 适合承担推理 + 工具决策循环能力，不要把它误用成所有流程控制的唯一承载。
2. 多智能体优先采用 **Tool Calling / Agent Tool** 模式做集中式编排，因为本产品更偏任务编排和结构化工作流，而不是让多个专家 Agent 直接轮流和用户自由对话。
3. `StateGraph` 应作为工作流骨架来承载：
   - 条件路由
   - 局部回路
   - Agent as Node
   - 普通 Node + Agent Node 混编
4. Human-in-the-Loop 要用于高风险工具动作，例如：
   - 写入或覆盖飞书文档
   - 发送 IM 消息
   - 触发外部副作用动作
5. checkpoint 要配合 HITL 和工作流恢复使用，不能只是做一个孤立的 Redis 封装。

### 6.1 你必须吸收的官方文档关键信息

以下内容是你改造时必须贯彻的框架事实：

1. `ReactAgent` 是一个基于循环推理与工具调用的生产级 Agent 实现，适合处理动态推理和工具选择。
2. Multi-agent 文档明确区分：
   - Tool Calling：Supervisor Agent 将其他 Agent 作为工具调用，适合集中式任务编排与结构化工作流。
   - Handoffs：适合 Agent 直接接管用户对话。
3. Workflow 文档明确支持：
   - `StateGraph`
   - `addConditionalEdges`
   - Agent 作为 Node
   - 普通 Node 与 Agent Node 混合编排
   - 并行边和聚合节点
4. HITL 文档明确支持：
   - 工具调用命中策略时触发中断
   - 状态通过 checkpoint 保存
   - 人工决策支持 `approve` / `edit` / `reject`

请把这些能力真正用于代码结构设计，而不是只把它们挂在说明文档里。

---

## 7. 你要完成的具体工作

你不是只输出建议，而是要**实际修改代码**。但必须按阶段做，避免一次性把系统改崩。

### 第一阶段：代码审视与调整方案落地

你先做以下事情：

1. 系统梳理当前模块依赖关系和调用链
2. 识别最重的边界污染点
3. 输出一版“现状 -> 目标”的迁移设计
4. 明确哪些类保留、哪些类迁移、哪些类废弃、哪些接口新增

这一阶段要形成结构化输出，写入项目内文档。

### 第二阶段：建立任务中枢骨架

至少完成：

1. 统一的 Task/Step/Artifact/Event/Approval 领域骨架
2. Store 层相应存储接口或最小实现
3. planner/harness/common 中现有旧模型的映射或兼容层

### 第三阶段：收敛 planner 为意图与规划层

至少完成：

1. 将当前 planner 中“supervisor + planning + result judge + advice”的职责拆清
2. 保留必要 prompt 能力，但去掉不合理的整轮评分闭环
3. 形成：
   - Intent Router
   - Task Planner
   - Replanner
   - Plan Gate

### 第四阶段：收敛 harness 为执行编排层

至少完成：

1. 用 `StateGraph` 重新梳理执行流
2. 区分普通 Node、Agent Node、Tool Node
3. 允许文档任务、PPT 任务、白板任务走不同路径
4. 支持条件边和局部回退

### 第五阶段：引入人工介入与高风险动作门禁

至少完成：

1. 对写文档、发消息等高风险工具调用加 HITL 策略
2. 基于 checkpoint 支持暂停与恢复
3. 用户确认结果能回写任务状态

### 第六阶段：清理 common 和外部接口

至少完成：

1. 清理 common 中过重的业务模型
2. 保留真正跨模块契约
3. 让 app/imGateway/planner/harness/skills/store 的边界更清晰

---

## 8. 设计约束

你在改造时必须遵守以下约束：

1. **不要为了“新架构好看”而大面积推翻现有可用代码**
   - 尽量复用已有测试、模板、工具封装、配置类

2. **不要把所有东西继续塞进 planner**
   - planner 只负责规划和重规划，不负责承担所有业务语义

3. **不要把所有东西都做成 Agent**
   - 能做普通 Node 的做普通 Node
   - 能做 Tool 的做 Tool
   - 只把确实需要推理循环的部分做成 ReactAgent

4. **不要保留固定顺序总流水线**
   - 必须引入条件分支和按任务类型动态编排

5. **不要让 common 继续膨胀**
   - 只有跨模块稳定契约才允许放进 common

6. **不要绕开官方文档自行发明框架用法**
   - 如有不确定之处，优先查官方文档而不是编译源码或猜内部实现

7. **不要把“前端展示模型”和“后端运行状态模型”做成两套割裂结构**

8. **不要用“统一评分后整轮重跑三次”作为主质量策略**
   - 改成局部 gate + 局部回退 + 人工确认

---

## 9. 交付物要求

你完成本次任务时，必须给出以下交付物：

1. 一份项目内迁移设计文档
   - 说明现状问题
   - 说明目标模块边界
   - 说明迁移策略

2. 实际代码改动
   - 不是只给建议

3. 关键类和关键接口的重构

4. 必要的测试更新
   - 至少覆盖重构后最关键的 planner/harness/store 行为

5. 最终变更说明
   - 按模块总结改了什么
   - 还剩哪些技术债
   - 下一步建议做什么

---

## 10. 你输出与执行时的工作方式

请按下面方式执行：

1. 先阅读文档和代码，不要一上来就改
2. 先输出一版简洁但具体的迁移计划
3. 再按阶段修改代码
4. 每完成一阶段，说明：
   - 改了哪些文件
   - 为什么这样改
   - 是否跑了测试
   - 还剩什么问题

如果你发现现有代码中某些设计和目标架构冲突严重，请直接指出，不要为了兼容表面结构而保留明显错误的抽象。

---

## 11. 验收标准

最终结果至少满足以下标准：

1. 看代码能清楚分辨：
   - 入口接入层
   - 任务中枢层
   - 规划层
   - 执行编排层
   - 工具层
   - 存储/同步基础设施层

2. `planner` 不再像一个万能总控模块

3. `harness` 不再是固定顺序流水线，而是能基于工作流做条件编排

4. `store` 不再只是 planner 附属 Redis 状态存储，而开始承担任务状态与 checkpoint 基础能力

5. `skills` 成为更明确的工具层

6. `common` 边界明显收敛

7. 至少存在一条清晰的：
   - IM 输入
   - 任务创建
   - 规划
   - 执行
   - 产物落库
   - 事件更新
   - 人工确认/恢复
   - 最终完成
   的完整主链路

---

## 12. 参考提示

你可以吸收现有代码中的这些资产，但不要被现状绑死：

1. `planner` 中的 prompt profiles 和 PromptTemplate 体系
2. `harness` 中已有的 document/presentation workflow 雏形
3. `skills` 中飞书文档和消息工具封装
4. `store` 中 Redis 与 checkpoint 相关基础设施

但你必须把它们重新摆进正确的位置。

---

## 13. 你的第一步

请先做三件事：

1. 阅读：
   - `C:\Users\14527\Desktop\lark-ai\docx\基于 IM 的办公协同智能助手.md`
   - `C:\Users\14527\Desktop\lark-ai\im_harness架构设计.md`
2. 审视当前各模块代码边界和依赖关系
3. 输出一版“架构迁移计划 + 首批要改动的类/接口列表”

在这之后，再开始实际改代码。

