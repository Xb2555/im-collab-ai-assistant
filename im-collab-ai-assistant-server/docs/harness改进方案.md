# Harness 改进方案

## 1. 背景

当前项目已经完成场景 A 和场景 B 的第一版，场景 C 的 harness 也已经具备基础执行能力，但从实际使用结果看，系统还存在一类很典型的问题：

1. 用户原始需求在 `planner -> runtime task -> harness` 链路中被逐步压缩、改写，最终执行目标偏离原始意图。
2. 交付物类型判断仍然存在关键词匹配和默认兜底过宽的问题，用户明确要文档时，系统仍可能拆出 `DOC + PPT` 混合卡片。
3. harness 的文档模板过于粗糙，缺少成熟的架构文档组织方式，生成质量不稳定。
4. 澄清问题虽然经过 Agent 输出，但现有 prompt 和 few-shot 过于固定，导致问题集模式化，且上下文注入不足。
5. 用户要求包含 Mermaid 图时，当前链路无法稳定生成、校验并写入 Mermaid 图。

这四个问题表面看分别属于 `planner`、`harness`、`prompt`、`template`，但本质上都属于同一个主题：

`harness 缺少一份稳定、可验证、可回溯的 execution contract。`

如果没有这份 contract，planner 生成的“计划摘要”“第一张卡片标题”“模板类型”“澄清问题”“是否需要图表以及图表类型”就会分别演化，各自成为事实来源，最终把执行链路带偏。

本文给出一版针对当前仓库的改进方案，重点围绕 harness 架构，而不是只做 prompt 微调。

## 2. 当前问题与代码证据

### 2.1 原始需求在执行阶段被弱化

用户原始需求是：

`帮我写一篇 harness 架构设计文档`

但当前进入执行视图后，任务标题已经变成：

`生成面向开发者的数据流转文档`

这说明系统在“澄清后重构任务目标”时，把补充约束当成了新的主目标，而没有保留原始需求作为最高优先级目标。

代码上已经能看到这种信号：

1. `PlannerViewAssembler` 优先使用第一张卡片标题作为任务标题，而不是原始需求或稳定 task brief。见 [PlannerViewAssembler.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-app/src/main/java/com/lark/imcollab/app/planner/assembler/PlannerViewAssembler.java:16)。
2. `TaskBridgeService` 在创建 runtime task 时，把 `rawInstruction` 写成了 `session.getPlanBlueprintSummary()`，而不是真实用户输入。见 [TaskBridgeService.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/service/TaskBridgeService.java:30)。
3. `DocumentWorkflowNodes.generateOutline` 虽然会优先使用 state 中的 `RAW_INSTRUCTION`，但上游一旦只把摘要或改写后的目标传下来，执行层就只能围绕压缩后的 brief 继续生成。见 [DocumentWorkflowNodes.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/service/DocumentWorkflowNodes.java:68)。

### 2.2 交付物类型判断不严谨

这部分不是“感觉像 contains”，而是代码里确实存在粗粒度 `contains` 路由：

1. `IntentRouter` 直接通过 `msg.contains("ppt") / contains("文档") / contains("方案")` 进行类型判断。见 [IntentRouter.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/intent/IntentRouter.java:16)。
2. 这类规则天然是“命中即路由”，缺少显式的排他语义。例如“技术方案文档”里包含“方案”，却不代表“要 PPT”。
3. `TaskBridgeService.resolveType()` 又会根据卡片集合判断 runtime task 是 `WRITE_DOC / WRITE_SLIDES / MIXED`，如果上游 plan 多拆了一张 PPT 卡片，就会直接升级成混合任务。见 [TaskBridgeService.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/java/com/lark/imcollab/planner/service/TaskBridgeService.java:52)。

这会导致一个很典型的问题：

`用户明确说“写一份文档”，但由于规划或关键词歧义，最终系统仍可能下发 DOC + PPT。`

### 2.3 模板过于薄，无法承载“架构设计文档”这种专业文体

当前 harness 文档模板的主要问题不是“样式不好看”，而是缺少结构性：

1. `dispatchDocTask` 默认把模板类型设成 `REPORT`。见 [DocumentWorkflowNodes.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/service/DocumentWorkflowNodes.java:58)。
2. `DocumentTemplateService.selectTemplate()` 仍然基于标题和描述做关键词匹配。见 [DocumentTemplateService.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/template/DocumentTemplateService.java:22)。
3. 当前 `technical-plan-template.md` 只是“背景/目标/方案/风险/分工/时间计划”的薄壳。见 [technical-plan-template.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/resources/templates/doc/technical-plan-template.md:1)。

这套模板适合“通用汇报文档”，不适合“harness 架构设计文档”“Spring AI 技术介绍”这类需要明确架构视图、模块职责、数据流、状态机、决策边界的专业技术文档。

### 2.4 澄清问题模式化

从 prompt 规则上看，澄清 agent 本身并没有硬编码“三个固定问题”，但当前 few-shot 和 supervisor 语义已经把模型强烈引导到了固定问题集。

关键证据：

1. `clarification-instruction` 只规定“1-3 个问题”，并未要求固定三问。见 [clarification-instruction.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/resources/prompt/profiles/default/v4/clarification-instruction.md:1)。
2. 但 `clarification-examples.md` 的 few-shot 几乎固定为“输出类型 / 时间范围 / 受众”。见 [clarification-examples.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/resources/prompt/profiles/default/v4/clarification-examples.md:1)。
3. `supervisor-instruction` 还要求 `intent` 包含“产物、受众、截止时间或时间范围”。见 [supervisor-instruction.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-planner/src/main/resources/prompt/profiles/default/v4/supervisor-instruction.md:1)。

因此当前系统会出现下面这种不合理行为：

`用户要求写一篇 Spring AI 技术介绍，系统仍然会问“是否有具体的截止时间或时间范围？”`

这个问题不是单纯“提示词写得差”，而是：

1. 缺少按任务类型区分的 slot schema。
2. 缺少“该槽位是否真的阻塞当前任务”的判断。
3. 缺少把 `workspaceContext / sourceScope / selectedMessages / 历史文档` 注入到澄清判定中的机制。

### 2.5 当前链路没有把 Mermaid 当成一等产物

用户明确要求“需要包含 Mermaid 数据流转图”，但系统当前很难稳定产出 Mermaid，根因不在于“模型能力不足”，而在于执行层没有把 Mermaid 建模成独立能力。

当前代码证据：

1. harness 里没有独立的 `diagram` / `mermaid` workflow node，搜索结果基本为空。
2. `DocumentWorkflowNodes` 只有 `generateOutline -> generateSections -> reviewDoc -> writeDocAndSync` 四步，没有“图表规划”“图语法生成”“语法校验”“图文合并”节点。见 [DocumentWorkflowNodes.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/service/DocumentWorkflowNodes.java:68)。
3. 当前模板也没有 Mermaid 专属槽位，例如 `technical-plan-template.md` 没有 `{mermaidDiagram}`、`{contextDiagram}`、`{dataFlowDiagram}` 这类位置。见 [technical-plan-template.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/resources/templates/doc/technical-plan-template.md:1)。
4. planner 的意图层和澄清层也没有显式图区需求字段，例如 `diagramRequirement`、`diagramType`、`diagramScope`。

因此当前系统即便“知道用户想要 Mermaid 图”，也只能把这个要求混在普通正文 prompt 里，结果通常会出现：

1. 根本不出 Mermaid。
2. 出一段伪 Mermaid 文本，但不符合语法。
3. Mermaid 被塞进正文某段里，没有稳定模板落点。
4. review 节点没有检查 Mermaid 是否存在、是否可渲染。

## 3. 根因总结

从 harness 架构视角看，当前问题可归为五个根因。

### 3.1 没有 Canonical Request Contract

系统目前至少存在四种“任务描述”：

1. `rawInstruction`
2. `planBlueprintSummary`
3. 第一张 `planCard.title`
4. harness 节点运行时拼出来的 prompt brief

这些描述之间没有严格主次关系，也没有不可覆盖字段，导致后面的层总能覆盖前面的层。

### 3.2 交付物类型缺少结构化表达

现在的类型判断混合了三种逻辑：

1. 关键词路由
2. plan card 类型聚合
3. 规划 prompt 中的自然语言拆卡

但缺少一个统一的结构化意图对象，例如：

```json
{
  "requestedArtifacts": ["DOC"],
  "allowedArtifacts": ["DOC"],
  "primaryArtifact": "DOC",
  "crossArtifactPolicy": "FORBID_UNLESS_EXPLICIT"
}
```

没有这层约束，planner 和 harness 都只能“猜”用户是否还想要 PPT。

### 3.3 harness 模板没有从“文档壳”升级为“文档策略”

当前模板是静态 Markdown 壳，不是成体系的文档策略。缺少：

1. 文档类型级目标
2. 必备章节
3. 章节写作约束
4. 图表/Mermaid 要求
5. 架构文档质量门禁
6. 适配不同读者的表述策略

### 3.4 澄清机制没有做到“基于槽位缺失 + 上下文注入 + 阻塞性评估”

当前 `missingSlots` 更像“模型建议补问什么”，而不是“执行层真正阻塞什么”。这会带来两个结果：

1. 不必要的问题被问出来。
2. 真正关键的问题反而没有被显式建模。

### 3.5 图表能力没有被建模为独立执行能力

当前系统默认把 Mermaid 视为“正文里顺手生成的一段字符串”，而不是独立产物能力。

这会导致：

1. 不知道什么时候必须画图，什么时候只是可选。
2. 不知道该生成 `flowchart`、`sequenceDiagram`、`stateDiagram` 还是 `erDiagram`。
3. 没有专门的语法校验和失败重试。
4. 没有独立 artifact，可观测性差。

## 4. 改进目标

本轮改造建议达成以下目标：

1. 原始需求在 planner、runtime、harness 全链路可追溯，且不会被计划标题覆盖。
2. 用户明确要求单一交付物时，planner 不再产出额外交付物卡片。
3. 技术文档模板升级为“结构化文档策略”，能稳定支撑架构设计类输出。
4. 澄清问题改为“按任务类型动态补槽”，而不是围绕固定三问打转。
5. Mermaid/图表需求变成显式能力，能稳定生成、校验并写入文档。
6. harness 获得一份稳定的 execution contract，后续接入 Doc/PPT/Whiteboard 都复用同一套机制。

## 5. 方案一：建立 Canonical Execution Contract

这是本轮最核心的改造。

### 5.1 新增统一契约对象

建议在 `common` 中新增 `ExecutionContract`，由 planner 生成，由 harness 消费。

建议字段：

```json
{
  "taskId": "string",
  "rawInstruction": "用户原始输入，不可覆盖",
  "clarifiedInstruction": "融合澄清后的完整目标",
  "taskBrief": "执行摘要，可展示，但不能替代 rawInstruction",
  "requestedArtifacts": ["DOC"],
  "allowedArtifacts": ["DOC"],
  "primaryArtifact": "DOC",
  "audience": "开发者",
  "timeScope": null,
  "constraints": ["必须包含 mermaid 数据流图"],
  "sourceScope": {},
  "contextRefs": [],
  "frozenAt": "2026-04-29T..."
}
```

### 5.2 字段职责约束

必须明确三层语义：

1. `rawInstruction`
   - 永远保存用户原话。
   - 只能新增引用，不能重写。
2. `clarifiedInstruction`
   - 在原始需求基础上叠加澄清答案形成。
   - 是执行层的第一输入。
3. `taskBrief`
   - 仅用于标题、摘要、列表展示。
   - 不得作为执行层唯一事实来源。

### 5.3 当前代码中的直接改造点

1. `TaskBridgeService.createTask()` 不应再把 `planBlueprintSummary` 写入 `rawInstruction`，而应写入真正的 canonical raw instruction。
2. `PlannerViewAssembler.resolveTitle()` 不应优先取第一张卡片标题，应该优先显示 `taskBrief` 或 `intentSnapshot.userGoal`，并保留原始需求入口。
3. `DocumentWorkflowNodes` 每个 LLM 节点都应显式使用 `clarifiedInstruction + constraints + sourceScope`，而不是只使用 outline title 或卡片标题。

### 5.4 对 harness 的直接收益

有了 contract 之后，harness 才能做到：

1. 节点 prompt 始终围绕用户原始目标。
2. 局部重试时不会因为标题漂移而跑偏。
3. review 节点可以反向检查“最终文档是否回答了原始需求”。

## 6. 方案二：把交付物类型改成结构化判定，而不是关键词猜测

### 6.1 当前问题

`IntentRouter` 的 `contains` 逻辑只适合作为兜底，不适合作为主判定。

原因：

1. “方案”“架构”“介绍”这类词在不同任务里语义差异很大。
2. 关键词命中不等于用户授权产生额外交付物。
3. 规划层的自然语言拆卡如果不受约束，后续 `TaskBridgeService` 会把混合卡片升级成混合任务。

### 6.2 改造建议

建议把“用户要什么交付物”从隐式猜测改成显式字段：

1. `requestedArtifacts`
   - 用户明确提到的交付物。
2. `allowedArtifacts`
   - 当前任务允许生成的交付物。
3. `primaryArtifact`
   - 当前主产物。
4. `crossArtifactPolicy`
   - `FORBID_UNLESS_EXPLICIT`
   - `ALLOW_SUGGEST_ONLY`
   - `ALLOW_AUTO_PLAN`

### 6.3 规则

对于当前产品，更安全的默认策略应是：

`FORBID_UNLESS_EXPLICIT`

即：

1. 用户明确说“写文档”，只能出 `DOC`。
2. 用户明确说“写 PPT”，只能出 `PPT`。
3. 用户明确说“文档和 PPT 都要”，才能出双卡片。
4. 如果 planner 想建议附加交付物，只能作为建议，不得自动变成 plan card。

### 6.4 校验门

建议新增 `ArtifactTypeGate`：

1. planner 出 plan 后先校验 card types 是否在 `allowedArtifacts` 内。
2. 若超出范围：
   - 自动裁剪非授权卡片。
   - 或回退到 replan。
3. harness 只消费通过 gate 的卡片。

## 7. 方案三：升级文档模板为“技术文档策略模板”

### 7.1 当前模板的问题

当前模板只是在做变量替换，不是在做专业技术文档生成。

对“harness 架构设计文档”这类任务，合理的结构至少应包含：

1. 问题背景
2. 设计目标
3. 架构原则
4. 模块分层
5. 数据流转
6. 状态机或时序
7. 关键接口 / 契约
8. 风险与边界
9. 演进路线

### 7.2 参考的公开 prompt 方案

本轮可借鉴两类公开资料：

1. docToolchain 的 `LLM Prompts for Architecture Documentation`
   - 强调 arc42、上下文图、质量场景、ADR、风险矩阵、持续文档化。
   - 链接：<https://doctoolchain.org/LLM-Prompts/>
2. presets.dev 的 `Epic Architecture Specification Prompt`
   - 强调目标、上下文、约束、输出结构、实现计划的完整性。
   - 链接：<https://presets.dev/>

这两类资料的共同特点不是“词藻华丽”，而是：

1. 先定义文档目的。
2. 再定义读者。
3. 再定义结构。
4. 最后定义输出约束。

### 7.3 模板升级方案

建议把当前 `templates/doc` 从“成品模板”升级为“两层模板”：

1. `skeleton template`
   - 定义章节框架和变量槽位。
2. `generation policy`
   - 定义每种文档如何写、必须覆盖什么、图表要求是什么。

例如为 `TECHNICAL_ARCHITECTURE` 新增专用策略：

1. 必须包含系统目标与非目标。
2. 必须包含模块职责表。
3. 必须包含至少一张 Mermaid 数据流图或时序图。
4. 必须说明与现有 planner/harness/skills/store 的边界。
5. 必须给出演进建议和未决问题。

### 7.4 建议新增模板类型

建议扩展 `DocumentTemplateType`：

1. `TECHNICAL_ARCHITECTURE`
2. `TECHNICAL_INTRODUCTION`
3. `ARCHITECTURE_REVIEW`

并用显式策略选择，而不是继续在标题里 `contains("技术") / contains("架构")`。

### 7.5 模板必须预留 Mermaid 专属槽位

对于架构设计和技术介绍类文档，建议模板显式包含：

1. `{contextDiagram}`
2. `{dataFlowDiagram}`
3. `{sequenceDiagram}`
4. `{stateDiagram}`
5. `{diagramNotes}`

但不是每篇文档都强塞全部图位，而是由 `templateStrategy` 指定：

1. `TECHNICAL_ARCHITECTURE`
   - 至少需要一张 `dataFlowDiagram` 或 `contextDiagram`
2. `TECHNICAL_INTRODUCTION`
   - 默认无强制图，但如果用户要求“配图/时序/流程”，必须至少补一张
3. `ARCHITECTURE_REVIEW`
   - 可要求“现状图 + 目标图”

## 8. 方案四：把 Mermaid 变成一等执行能力

### 8.1 不再把 Mermaid 混在正文生成里

建议在 harness 中引入 Mermaid 专职节点，而不是继续让 `generateSections` 顺手产图。

推荐工作流：

1. `plan_diagrams`
   - 根据 `ExecutionContract` 判断是否需要图、需要几张图、每张图的类型和作用
2. `generate_mermaid`
   - 针对每张图独立生成 Mermaid 源码
3. `validate_mermaid`
   - 进行语法和结构校验
4. `attach_diagrams`
   - 将图源码与正文合并
5. `review_doc`
   - 额外检查 Mermaid 是否存在且与正文一致

### 8.2 contract 中显式加入图区需求

建议在 `ExecutionContract` 中新增字段：

```json
{
  "diagramRequirement": {
    "required": true,
    "types": ["DATA_FLOW"],
    "format": "MERMAID",
    "placement": "正文内嵌",
    "count": 1
  }
}
```

对于“需要包含 Mermaid 数据流图”这种需求，planner 不应只把它放进 `constraints` 文本里，而应提升为结构化字段。

### 8.3 review gate 增加 Mermaid 校验

建议新增 `DiagramGate`，至少检查：

1. 是否真的生成了 Mermaid 源码。
2. Mermaid 代码块是否使用合法 fenced block，如 ```` ```mermaid ````。
3. 图类型是否符合要求，如 `flowchart TD` / `sequenceDiagram`。
4. 图中节点与正文模块名是否基本一致。
5. 用户明确要求 Mermaid 时，未生成 Mermaid 视为 gate fail。

### 8.4 artifact 层把 Mermaid 独立持久化

建议将 Mermaid 源码独立保存为 artifact，而不是只混在最终文档 Markdown 里。

例如：

1. `ArtifactType.DIAGRAM_SOURCE`
2. `ArtifactType.DOC_LINK`

这样可以做到：

1. 单独回看 Mermaid 源码
2. Mermaid 失败时只重试图表节点
3. 后续支持从 Mermaid 再渲染到白板/PPT

### 8.5 对 harness 的收益

一旦 Mermaid 成为一等能力，系统就可以：

1. 在架构文档场景下稳定地产出图，而不是“看模型发挥”。
2. 在失败时局部重试图表节点，而不是整篇文档重写。
3. 为后续场景 D 的 PPT/白板复用同一份图源。

## 9. 方案五：把澄清机制改成 Slot-Based Clarification

### 8.1 改造方向

澄清问题不应是“固定三问”，而应是：

`按任务类型定义槽位 schema -> 基于上下文判断哪些槽位缺失 -> 仅追问会阻塞执行的槽位`

### 8.2 不同任务类型的槽位应不同

例如：

#### 文档类技术架构任务

必选或强相关槽位：

1. `audience`
2. `must_include_sections`
3. `diagram_requirement`
4. `source_scope`
5. `style_depth`

可选槽位：

1. `deadline`
2. `page_limit`
3. `output_language`

#### 内容整理类周报/会议纪要

必选或强相关槽位：

1. `time_scope`
2. `source_scope`
3. `audience`

### 8.3 什么时候该问“时间范围”

“时间范围”应该从“通用必问项”降级为“上下文依赖项”：

1. 当任务是“整理最近讨论/最近会议/本周进展”时，时间范围是关键槽位。
2. 当任务是“写一篇 Spring AI 技术介绍”时，时间范围不阻塞写作，不应该作为默认问题。
3. 当任务需要从 IM 历史消息抽取内容时，时间范围可能是 `source_scope` 的一个子字段，而不是独立用户问题。

### 8.4 上下文注入机制

建议把以下信息统一注入 intent / clarification 阶段：

1. `WorkspaceContext.selectedMessages`
2. `WorkspaceContext.timeRange`
3. `WorkspaceContext.chatId/threadId/messageId`
4. 已选文档、已有草稿、当前任务历史
5. 既有架构文档，例如 `docs/im_harness架构设计.md`

让 agent 先判断：

1. 这些上下文是否已经回答了某个槽位。
2. 如果回答了，就不要再问。
3. 如果没有回答，再决定是否追问。

### 8.5 Prompt 侧改造建议

当前 few-shot 过度强化了“输出类型 + 时间范围 + 受众”。

建议：

1. 删除固定三问示例。
2. 增加“只问一个问题”的案例。
3. 增加“无需追问，直接进入规划”的案例。
4. 增加“从上下文中自动填槽，不再向用户提问”的案例。

## 10. harness 架构上的落地改造

### 9.1 在 planner 和 harness 之间增加 Intake Gate

建议新增内部 `ExecutionIntakeGate`，位于 `CONFIRM_EXECUTE -> harness.startExecution` 之间。

职责：

1. 读取 `ExecutionContract`
2. 校验交付物类型
3. 校验关键槽位是否已满足
4. 生成最终 `ExecutionBrief`
5. 将 contract 冻结后再交给 harness

### 9.2 harness 只消费冻结后的 contract

harness 的每个节点都不应自己再“猜任务是什么”。

节点只读取：

1. `clarifiedInstruction`
2. `constraints`
3. `sourceScope`
4. `templateStrategy`
5. `allowedArtifacts`
6. `diagramRequirement`

这样能避免：

1. 节点自己从标题推测任务。
2. 节点自己重新选择模板。
3. 局部重试时语义漂移。

### 10.3 review 节点新增“原始需求对齐检查”

当前 `reviewDoc` 主要检查结构覆盖项，不检查“有没有回答原始需求”。

建议新增两个 review 维度：

1. `goal_alignment`
   - 文档是否真的在写 “harness 架构设计”，而不是泛化成“数据流转文档”。
2. `artifact_scope_alignment`
   - 是否出现了未授权的交付物扩展，如自动延伸到 PPT。
3. `diagram_alignment`
   - 用户要求 Mermaid 时，是否真的生成了正确类型的 Mermaid 图。

### 10.4 templateStrategy 下沉为 state

当前 `dispatchDocTask` 直接把 `TEMPLATE_TYPE` 初始化为 `REPORT`，这会污染整个执行链路。

建议改成：

1. `ExecutionIntakeGate` 决定 `templateStrategy`
2. 写入 workflow state
3. `DocumentWorkflowNodes` 只消费该值

### 10.5 Mermaid 节点独立进入 workflow

建议把场景 C 文档工作流从当前四节点升级为：

1. `dispatch_doc_task`
2. `generate_outline`
3. `generate_sections`
4. `plan_diagrams`
5. `generate_mermaid`
6. `validate_mermaid`
7. `review_doc`
8. `write_doc_and_sync`

这样才能真正保证“需要 Mermaid 图”不是一句愿望，而是可执行、可校验、可重试的节点链路。

## 11. 分阶段实施建议

### 10.1 第一阶段：止血

目标：先避免明显跑偏。

建议：

1. `TaskBridgeService` 改为保存真实 `rawInstruction`
2. `PlannerViewAssembler` 不再优先使用第一张卡片标题作为任务标题
3. `IntentRouter` 的 `contains` 逻辑降级为兜底
4. `dispatchDocTask` 不再默认 `REPORT`
5. 修改澄清 few-shot，去掉固定三问模式
6. 技术架构模板先补 Mermaid 槽位

### 10.2 第二阶段：建立结构化 contract

目标：让 planner 和 harness 之间的语义稳定下来。

建议：

1. 新增 `ExecutionContract`
2. 新增 `ExecutionIntakeGate`
3. 新增 `ArtifactTypeGate`
4. 引入 `crossArtifactPolicy`
5. 引入 `diagramRequirement`

### 10.3 第三阶段：升级模板体系

目标：把架构文档和技术介绍类文档做出稳定质量。

建议：

1. 扩展 `DocumentTemplateType`
2. 新增 `template strategy` 配置
3. 引入 Mermaid、模块职责表、状态机、风险章节策略
4. 把公开最佳实践转成内部模板资产

### 10.4 第四阶段：升级澄清与上下文注入

目标：让提问变成“按需提问”。

建议：

1. 按任务类型建立 slot schema
2. 在 intent 阶段注入 `WorkspaceContext` 和已有文档引用
3. 对每个 slot 建立 `blocking / optional / inferable` 三态
4. 提问前先跑 `slot satisfiability check`

### 11.5 第五阶段：补齐 Mermaid 专职能力

目标：让 Mermaid 真正可生成、可校验、可复用。

建议：

1. 新增 `plan_diagrams / generate_mermaid / validate_mermaid` 节点
2. 把 Mermaid 源码独立保存为 artifact
3. review gate 强制校验 Mermaid 存在性和语法
4. 后续将 Mermaid artifact 复用于场景 D

## 12. 预期收益

完成上述改造后，系统应当具备以下效果：

1. 用户说“写一篇 harness 架构设计文档”，最终标题、摘要、执行 prompt、review 目标都围绕这个任务，不会被补充约束篡位。
2. 用户说“只要文档”，系统不会再自行吐出 `DOC + PPT` 双卡片。
3. 架构设计类文档会有明显更强的结构性，至少不再只是“背景/目标/方案/风险”通用壳。
4. 澄清问题会更少、更准，并且能体现上下文感知。
5. 用户要求 Mermaid 图时，系统能稳定生成可校验的 Mermaid，而不是随机遗漏。

## 13. 结论

这轮问题不能只靠“改几句 prompt”解决。

真正需要改的是：

1. planner 到 harness 之间的语义契约
2. 交付物类型的结构化表达
3. 文档模板从壳升级为策略
4. Mermaid 从“正文附带文本”升级为独立能力节点
5. 澄清机制从固定问题升级为 slot-based clarification

建议优先级如下：

1. 先修 `rawInstruction` 漂移和交付物类型失真
2. 再建立 `ExecutionContract`
3. 再把 Mermaid 和模板体系一起升级
4. 最后完善动态澄清与上下文注入

只有这样，harness 才能真正成为执行层，而不是继续放大 planner 前置误差的“误差扩音器”。
