# document-iteration 新架构设计方案

## 1. 文档定位

本文是给 Codex 直接用于改代码的实施型架构文档，不是讨论稿。

目标：

1. 一次性重构 `/api/planner/tasks/document-iteration` 链路。
2. 从根源上解决当前实现中“定位不稳定、替换策略混乱、verify 补丁堆积、结构误改、重复标题、标题层级错乱、命中漂移”等问题。
3. 使整个文档迭代能力与飞书 CLI 的真实最小可操作颗粒度严格对齐。
4. 保持与当前项目 `planner / harness / store / skills` 架构一致，符合 `im_harness架构设计.md` 的核心思想。

本文默认读者：

1. Codex
2. 当前项目维护者
3. 熟悉当前 Java 项目结构、Spring AI Alibaba、Lark CLI 的开发者

---

## 2. 必须先读的前置上下文

### 2.1 本地项目文档

1. [docs/im_harness架构设计.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/docs/im_harness架构设计.md)
2. [docs/0501_文档迭代实现方案.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/docs/0501_文档迭代实现方案.md)
3. [docs/0502_文档迭代进度.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/docs/0502_文档迭代进度.md)
4. [docs/0503_docx生成链路整改方案.md](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/docs/0503_docx生成链路整改方案.md)

### 2.2 当前文档迭代核心代码

1. [DefaultDocumentIterationExecutionService.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/service/DefaultDocumentIterationExecutionService.java)
2. [DocumentIterationIntentService.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentIterationIntentService.java)
3. [DocumentAnchorIntentService.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentAnchorIntentService.java)
4. [DocumentTargetLocator.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentTargetLocator.java)
5. [DocumentStructureParser.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentStructureParser.java)
6. [DocumentEditPlanBuilder.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentEditPlanBuilder.java)
7. [DocumentPatchExecutor.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentPatchExecutor.java)
8. [LarkDocTool.java](/Users/linrunxinnnn/Desktop/lark_im/im-collab-ai-assistant/im-collab-ai-assistant-server/im-collab-ai-assistant-skills/src/main/java/com/lark/imcollab/skills/lark/doc/LarkDocTool.java)

### 2.3 飞书 CLI 文档边界

实现必须严格以以下事实为约束：

1. `str_replace`
   - 本质是文本级替换
   - XML 模式只支持行内匹配
   - Markdown 模式支持跨行匹配，但本质仍然是文本替换，不是结构编辑
2. `block_insert_after`
   - 本质是 block 级“后插”
   - 不提供 `insert_before`
3. `block_replace`
   - 本质是替换某个 block
   - 适合整块重建，不适合作为前插语义的通用模拟器
4. `block_delete`
   - 本质是删除 block
5. `block_move_after`
   - 本质是结构重排
6. `append`
   - 本质等价于 `block_insert_after --block-id -1`

因此：

1. 文本级操作与结构级操作必须分层建模。
2. 不允许继续用 `block_replace` 通吃“before insert”“prepend metadata”“结构前插”等平台未原生支持的语义。

---

## 3. 当前实现的根因性问题

### 3.1 当前实现不是“结构编辑器”，而是“patch 命令拼装器”

当前链路大致是：

1. LLM 先判断 `intentType`
2. LLM 再判断 `LOCATOR_STRATEGY / RELATIVE_POSITION / LOCATOR_VALUE`
3. `DocumentTargetLocator` 再用字符串、标题、ordinal、小节规则去猜 block 或 excerpt
4. `DocumentEditPlanBuilder` 再猜应该选 `STR_REPLACE / BLOCK_REPLACE / BLOCK_INSERT_AFTER / APPEND / BLOCK_DELETE`
5. `DocumentPatchExecutor` 再对不同 patch 类型分别补 verify 规则

问题：

1. 同一个编辑意图会被映射成多套 patch 方案。
2. 定位结果不是稳定结构对象，而是脆弱的文本/标题命中结果。
3. verify 关注的是“本次用了哪条命令”，而不是“最终文档状态是否满足编辑目标”。

### 3.2 定位层和 patch 层耦合严重

当前代码里：

1. `matchedExcerpt` 既用于前端 preview
2. 又用于 `oldText`
3. 又用于 before/after 的结构拼接
4. 还被拿来做 verify

这是错误的。

`preview`、`anchor text`、`replace pattern`、`structural boundary` 不是一回事，必须拆开。

### 3.3 平台不支持的语义被用 patch 欺骗模拟

典型例子：

1. “在文章开头插入作者信息”
2. “在某标题前插入一节”

当前实现会：

1. 把“新增内容 + 原标题”拼成一个 `generatedContent`
2. 再用 `block_replace` 去重写目标 block

这种做法会直接导致：

1. 重复标题
2. 标题层级漂移
3. 原 block 语义丢失
4. verify 逻辑被迫不断加补丁

### 3.4 DocumentStructureParser 已经演化成规则堆

它当前承担了太多与平台编辑能力无关的职责：

1. 标题抽取
2. 标题归一化
3. 中文序号解析
4. “第几小节”猜测
5. decimal heading 推断
6. heading granularity 推断

这说明系统没有稳定的结构模型，只能用文本规则补。

---

## 4. 新架构的总原则

### 4.1 一句话定义

新的 document-iteration 应该是：

**一个以结构快照为事实基础、以编辑语义为一等公民、以锚点类型系统隔离文本与结构操作、以策略矩阵将编辑意图映射到飞书 CLI 原生命令、并以目标状态校验作为最终成功判定的文档结构编辑系统。**

### 4.2 五条硬原则

1. **编辑语义优先于 patch 命令**
   不允许 patch 类型倒推业务语义。

2. **结构快照优先于字符串启发式**
   所有定位必须先构建统一结构视图，再在结构视图上做解析。

3. **锚点类型必须显式区分**
   文本锚点、block 锚点、section 锚点不能混用。

4. **平台不支持的语义不能再靠 patch 欺骗模拟**
   平台不支持 `insert_before`，那就必须用受控的结构重排方案，而不是偷偷拼旧标题后 `block_replace`。

5. **成功判定按目标状态，不按命令种类**
   verify 不能再围绕 `verifyBlockReplace` 这种函数堆规则。

---

## 5. 新的分层设计

整个 document-iteration 新链路重构为六层：

1. `Intent Layer`
2. `Structure Snapshot Layer`
3. `Anchor Resolution Layer`
4. `Edit Strategy Layer`
5. `Patch Compile / Execute Layer`
6. `Target State Verify Layer`

### 5.1 Intent Layer

职责：

1. 把自然语言编辑指令解析成高层编辑语义
2. 不负责 patch 命令选型
3. 不负责 block id 定位

输出不再是：

1. `LOCATOR_STRATEGY`
2. `RELATIVE_POSITION`

而是新的高层命令：

1. `INSERT_INLINE_TEXT`
2. `INSERT_BLOCK_AFTER_ANCHOR`
3. `INSERT_METADATA_AT_DOCUMENT_HEAD`
4. `INSERT_SECTION_BEFORE_SECTION`
5. `APPEND_SECTION_TO_DOCUMENT_END`
6. `REWRITE_INLINE_TEXT`
7. `REWRITE_SINGLE_BLOCK`
8. `REWRITE_SECTION_BODY`
9. `DELETE_INLINE_TEXT`
10. `DELETE_BLOCK`
11. `DELETE_SECTION_BODY`
12. `DELETE_WHOLE_SECTION`
13. `MOVE_SECTION`
14. `MOVE_BLOCK`
15. `EXPLAIN_ONLY`

要求：

1. 这一步依然可以使用 LLM。
2. 但它只返回“编辑语义枚举 + 参数槽位”，不能直接返回具体 patch 类型。

### 5.2 Structure Snapshot Layer

职责：

1. 每次编辑前统一抓取文档结构快照
2. 生成一份可重复使用的结构模型

必须读取：

1. `outline`
2. `full with-ids`
3. 按需 `section`
4. 按需 `range`

生成模型：

#### `DocumentStructureSnapshot`

建议字段：

1. `docId`
2. `revisionId`
3. `rootNodes`
4. `headingIndex`
5. `blockIndex`
6. `topLevelSequence`
7. `rawOutlineXml`
8. `rawFullXml`
9. `rawFullMarkdown`

#### `DocumentNode`

建议字段：

1. `blockId`
2. `blockType`
3. `headingLevel`
4. `titleText`
5. `plainText`
6. `parentBlockId`
7. `children`
8. `topLevelAncestorId`
9. `prevSiblingId`
10. `nextSiblingId`
11. `rangeStart`
12. `rangeEnd`

要求：

1. Snapshot 一旦生成，本次编辑流程中的定位、规划、verify 都必须引用它。
2. 不允许中途再从零散 fetch 结果直接拼逻辑。

### 5.3 Anchor Resolution Layer

职责：

1. 基于 `DocumentStructureSnapshot` 解析用户要编辑的真实锚点
2. 不负责 patch 选型

锚点类型必须显式区分：

#### `TextAnchor`

适用于：

1. `str_replace`
2. 文本级 explain

字段：

1. `matchedText`
2. `matchCount`
3. `surroundingContext`
4. `sourceBlockIds`

#### `BlockAnchor`

适用于：

1. `block_insert_after`
2. `block_replace`
3. `block_delete`
4. `block_move_after`

字段：

1. `blockId`
2. `blockType`
3. `plainText`
4. `topLevelAncestorId`
5. `nextBlockId`
6. `prevBlockId`

#### `SectionAnchor`

适用于：

1. section body rewrite
2. section delete
3. section move
4. section 前后插

字段：

1. `headingBlockId`
2. `headingText`
3. `headingLevel`
4. `bodyBlockIds`
5. `allBlockIds`
6. `topLevelIndex`
7. `prevTopLevelSectionId`
8. `nextTopLevelSectionId`

要求：

1. 定位层不能再返回 `matchedExcerpt` 这种模糊对象。
2. 不能让 `preview` 兼任 `replace pattern`。

### 5.4 Edit Strategy Layer

职责：

1. 把编辑语义和锚点类型映射成唯一允许的策略
2. 这是新链路的核心

#### 5.4.1 策略矩阵

必须实现一张显式矩阵，而不是 if/else 猜 patch。

例如：

1. `REWRITE_INLINE_TEXT`
   - 允许锚点：`TextAnchor`
   - 允许 patch：`str_replace`

2. `INSERT_BLOCK_AFTER_ANCHOR`
   - 允许锚点：`BlockAnchor`
   - 允许 patch：`block_insert_after`

3. `REWRITE_SINGLE_BLOCK`
   - 允许锚点：`BlockAnchor`
   - 允许 patch：`block_replace`

4. `DELETE_BLOCK`
   - 允许锚点：`BlockAnchor`
   - 允许 patch：`block_delete`

5. `DELETE_SECTION_BODY`
   - 允许锚点：`SectionAnchor`
   - 允许 patch：`block_delete(bodyBlockIds)`

6. `DELETE_WHOLE_SECTION`
   - 允许锚点：`SectionAnchor`
   - 允许 patch：`block_delete(allBlockIds)`

7. `MOVE_SECTION`
   - 允许锚点：`SectionAnchor`
   - 允许 patch：`block_move_after`

8. `INSERT_METADATA_AT_DOCUMENT_HEAD`
   - 允许锚点：`Document head anchor` 或 `SectionAnchor(first top-level section)`
   - 允许 patch：受控的 `block_insert_after + block_move_after` 组合
   - 不允许 patch：`block_replace` 模拟前插

9. `INSERT_SECTION_BEFORE_SECTION`
   - 允许锚点：`SectionAnchor`
   - 允许 patch：受控两步法
     - 新建 section block
     - 再 `block_move_after` 到前序锚点之后或通过头部锚点定位
   - 不允许 patch：把“新节 + 原标题”拼进 `block_replace`

#### 5.4.2 平台能力受限时的处理

如果飞书 CLI 没有原生命令直接支持某个语义：

1. 优先寻找合法结构等价变换
2. 再进入审批或 fallback
3. 绝对不允许继续用文本/标题拼接来欺骗 patch

### 5.5 Patch Compile / Execute Layer

职责：

1. 把确定好的策略编译成一个或多个飞书 CLI patch
2. 执行 patch

这一层不再负责：

1. 编辑语义理解
2. 锚点选择
3. 策略选择

它只负责：

1. patch 序列化
2. revision 透传
3. 执行结果收集

输出：

#### `CompiledPatchPlan`

字段：

1. `strategyType`
2. `patchOperations`
3. `expectedState`
4. `safetyLevel`
5. `requiresApproval`

### 5.6 Target State Verify Layer

职责：

1. 不再按命令类型 verify
2. 按目标状态 verify

禁止继续保留的思路：

1. `verifyBlockReplace`
2. `verifyBlockInsert`
3. `verifyStringReplace`

这些函数可以保留底层辅助校验，但不能再成为最终架构中心。

新的 verify 模型：

#### `ExpectedDocumentState`

示例类型：

1. `EXPECT_TEXT_REPLACED`
2. `EXPECT_BLOCK_INSERTED_AFTER`
3. `EXPECT_SECTION_BODY_REMOVED`
4. `EXPECT_SECTION_MOVED`
5. `EXPECT_METADATA_BLOCK_PRESENT_AT_HEAD`
6. `EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION`

验证逻辑只关心：

1. 最终结构是否达到目标
2. revision 是否推进
3. 关键锚点是否仍满足结构一致性

---

## 6. 新的数据结构设计

### 6.1 替换当前 `DocumentTargetSelector`

不再让一个对象同时承载：

1. preview
2. locator text
3. block ids
4. matched excerpt

改成：

#### `DocumentEditIntent`

字段：

1. `intentType`
2. `semanticAction`
3. `userInstruction`
4. `parameters`

#### `ResolvedDocumentAnchor`

字段：

1. `anchorType`
2. `textAnchor`
3. `blockAnchor`
4. `sectionAnchor`

#### `DocumentEditStrategy`

字段：

1. `strategyType`
2. `anchorType`
3. `patchFamily`
4. `generatedContent`
5. `expectedState`
6. `requiresApproval`
7. `riskLevel`

#### `DocumentIterationPlanVO`

对外返回时也要升级，至少明确展示：

1. `semanticAction`
2. `anchorType`
3. `strategyType`
4. `expectedState`

不能再只暴露 `toolCommandType`，否则前端永远看到的是 CLI 命令，不是业务语义。

---

## 7. 典型场景的正确实现方式

### 7.1 在文章开头插入作者信息

语义：

1. `INSERT_METADATA_AT_DOCUMENT_HEAD`

正确做法：

1. 识别为“文档头部 metadata block 插入”
2. 定位文档首个 top-level section
3. 编译成受控结构策略
4. 目标状态是：
   - metadata block 出现在第一节之前
   - 原第一节 heading 不被复制、不被吞掉、不被重写

禁止做法：

1. 生成 `作者信息 + ## 一、项目背景`
2. 用 `block_replace` 重写原 heading block

### 7.2 在某章节前插入一节

语义：

1. `INSERT_SECTION_BEFORE_SECTION`

正确做法：

1. 目标锚点必须是 `SectionAnchor`
2. 不允许只拿 heading 文本拼 replacement
3. 应通过合法结构移动或合法前序锚点插入实现

### 7.3 改写某一句原文

语义：

1. `REWRITE_INLINE_TEXT`

正确做法：

1. 只允许 `TextAnchor`
2. 只允许 `str_replace`
3. 只能在唯一命中时执行

### 7.4 删除某一节正文但保留标题

语义：

1. `DELETE_SECTION_BODY`

正确做法：

1. 目标必须是 `SectionAnchor`
2. patch 是 `block_delete(bodyBlockIds)`
3. verify 目标是“heading 存在，body blocks 不存在”

---

## 8. 需要删除或降级的旧设计

以下旧设计不得继续作为主路径保留：

1. `DocumentAnchorIntentService` 输出 `LOCATOR_STRATEGY / RELATIVE_POSITION / LOCATOR_VALUE`
2. `DocumentTargetLocator` 中的多层 fallback 文本猜测
3. `DocumentStructureParser.resolveOrdinalHeading()` 这类规则驱动定位主路径
4. `DocumentEditPlanBuilder` 中基于是否拿到 `anchorBlockId` 决定 `BLOCK_REPLACE / STR_REPLACE` 的 before-insert 兜底
5. `DocumentPatchExecutor` 中以 patch 种类为中心的 verify 逻辑

说明：

1. 某些正则或文本归一化工具函数可以保留为辅助能力。
2. 但它们不能再作为架构中心。

---

## 9. 与 `im_harness` 架构的关系

### 9.1 完全符合的点

本方案符合：

1. `Planner / Orchestrator` 是唯一大脑层
2. 编排对象是步骤图，不是固定黑箱主节点
3. Gate 分阶段生效
4. 工具能力显式输入输出
5. 失败优先局部回退
6. 状态和 Artifact 可显式沉淀

### 9.2 不违反的点

本方案没有回退成：

1. 单黑箱大模型重写全文
2. 固定顺序串行跑子 Agent 的流水线
3. 第二个 supervisor/harness 主脑

### 9.3 这次整改的正确理解

这次整改不是：

1. 修 `block_replace` 的一个 bug
2. 再加几条 heading 规则

而是：

1. 把 document-iteration 从“命令拼装系统”升级为“结构语义编辑系统”

---

## 10. 代码改造总要求

### 10.1 必须新增的核心组件

至少新增：

1. `DocumentEditIntentResolver`
2. `DocumentStructureSnapshotBuilder`
3. `DocumentAnchorResolver`
4. `DocumentEditStrategyPlanner`
5. `DocumentPatchCompiler`
6. `DocumentTargetStateVerifier`

### 10.2 必须重构的现有组件

1. `DefaultDocumentIterationExecutionService`
   - 改成调用新六层
2. `LarkDocTool`
   - 保持工具层，但要支持 snapshot 构建所需原始数据拉取
3. `DocumentIterationPlanVO`
   - 对外展示语义策略和目标状态

### 10.3 必须删除的耦合

1. 定位层直接选择 patch 类型
2. patch builder 反向依赖 preview/excerpt
3. verify 与 patch family 的强耦合

---

## 11. 一次性交付验收标准

本次架构改造完成后，必须同时满足：

1. 任一编辑请求都先生成结构快照。
2. 任一编辑请求都先得到高层编辑语义，再解析锚点，再规划 patch。
3. 文本级编辑与结构级编辑使用不同锚点类型，不能混用。
4. “文首插入 metadata”“标题前插入 section”不得再通过 `block_replace` 拼接原标题实现。
5. 对外返回的 plan 中，必须能看到：
   - `semanticAction`
   - `anchorType`
   - `strategyType`
   - `expectedState`
6. verify 必须按目标状态判断成功，不得再以 patch 类型为中心加补丁。
7. 当前已知错误场景必须自然消失：
   - 重复插入 `## 一、项目背景`
   - 标题层级被吃掉
   - `str_replace` / `block_replace` 因语义漂移反复补洞
   - “第几小节”命中错误后靠模型再猜 block

---

## 12. 给 Codex 的最终实施口径

如果你是 Codex，请按以下口径执行：

1. 这不是局部打补丁任务，而是 document-iteration 架构整体重构任务。
2. 你必须一次性把定位、替换、verify 三层都改到新模型上，不能只改其中一层。
3. 你必须以飞书 CLI 的真实最小颗粒度为边界设计新策略，而不是继续发明 patch 兜底技巧。
4. 你必须显式建立：
   - 结构快照模型
   - 锚点类型系统
   - 编辑语义模型
   - 策略矩阵
   - 目标状态 verify
5. 你不得继续保留“标题文本 + 旧标题拼接 + block_replace 模拟 before insert”这条旧路。
6. 你不得继续把 `matchedExcerpt` 当作 preview、pattern、replace source、verify source 的混合字段。
7. 你不得再围绕 `verifyBlockReplace` 一类函数继续堆规则。

一句话总结：

**必须把当前 document-iteration 从“基于 patch 命令补丁的实现”重构成“基于结构语义和目标状态的编辑系统”，并一次性完成定位、替换、校验三层改造。**
