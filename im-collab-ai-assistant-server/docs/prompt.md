你现在要接手 `document-iteration` 架构整改。不要做局部修补，不要做兼容层叠加，不要继续补旧 locator/selector/patch-excerpt 体系，允许你把文档迭代链路的一切代码进行推翻重构。目标是在**不碰 Planner 完成态编排边界**的前提下，把 Doc Agent / `document-iteration` 内核一次性重构到可长期维护的状态。

你必须遵守以下协作边界：

1. 你负责 `harness/document`、`document/iteration`、文档编辑 prompt、定位、patch、verify、飞书 doc 工具调用。
2. 入口 `DocumentIterationExecutionService.execute(DocumentIterationRequest)` 尽量保持稳定。
3. 不要在 Doc Agent 内部决定“保留旧版还是新建一份文档”，这属于 Planner 策略。
4. 不要修改 `/commands REPLAN`、完成态 task 编排、`PlannerController` 的 commands 分支。
5. 如果必须调整共享 contract，优先做向后兼容新增；但这次任务默认**不以兼容旧垃圾链路为目标**。

下面是当前代码已经暴露出的真实问题。你必须把这些问题视为整改输入，而不是参考意见。

---

## 一、当前代码与目标架构的核心偏差

### 1. 主服务表面接入新架构，实际仍未完全摆脱旧世界

文件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/service/DefaultDocumentIterationExecutionService.java`

现状：

1. 服务层已经串起：
   - `DocumentEditIntentResolver`
   - `DocumentStructureSnapshotBuilder`
   - `DocumentAnchorResolver`
   - `DocumentEditStrategyPlanner`
   - `DocumentPatchCompiler`
   - `RichContentExecutionPlanner`
   - `RichContentExecutionEngine`
2. 但 `toPlanVO()` 仍然从 `selector.locatorValue` 和 `selector.matchedExcerpt` 取 `targetTitle/targetPreview`。
3. 这说明对外 plan 展示仍依赖旧 `DocumentTargetSelector`，新 `resolvedAnchor` 还不是唯一真相源。

结论：

1. `DocumentTargetSelector` 没有真正退场。
2. 计划展示层仍与旧定位语义耦合。
3. 富媒体 target 可见性不稳定，本质上不是前端问题，而是 plan model 仍然双轨。

### 2. `DocumentEditIntentResolver` 仍然是“高层意图旧服务 + LLM 补语义”的半成品

文件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentEditIntentResolver.java`

现状：

1. 先调用 `DocumentIterationIntentService.resolve(instruction)` 决定 `intentType`。
2. 再用另一个 prompt 猜 `semanticAction`。
3. 再用另一个 prompt 产出 `targetRegion/targetSemantic/targetKeywords`。
4. 失败时本地 fallback 直接退成：
   - `INSERT_BLOCK_AFTER_ANCHOR`
   - `REWRITE_INLINE_TEXT`
   - `DELETE_INLINE_TEXT`
   - `MOVE_BLOCK`

问题：

1. `intentType` 与 `semanticAction` 的来源被拆成多段，语义一致性无法保证。
2. fallback 仍然会在没有明确证据时直接给出可执行动作，不是 `unknown/unresolved/clarification`。
3. `assetSpec` 只覆盖了图片的 caption/alt/source/generationPrompt，表格和白板没有形成稳定结构化规格。

结论：

必须把意图识别收敛成**一次结构化输出**：

1. `intentType`
2. `semanticAction`
3. anchor slots
4. content generation slots
5. assetSpec
6. risk / clarification hints

不能继续分三次 prompt 拼语义。

### 3. `DocumentAnchorResolver` 仍然大量依赖文本启发式和 parser 业务规则

文件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentAnchorResolver.java`
- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentStructureParser.java`

现状：

1. `resolveSectionAnchor()` 仍调用：
   - `structureParser.resolveOrdinalHeading(instruction, headings)`
   - `structureParser.matchHeadings(instruction, headings)`
2. `resolveBlockAnchor()` 仍按 `plainText.contains(quoted)` 命中 block。
3. `resolveMediaAnchor()` 仍按 `blockType.contains("media"|"table"|"whiteboard")` 和 `plainText.contains(quoted)` 猜媒体节点。
4. `resolveTextAnchor()` 只是从引号里抽文本，再做全文次数统计，`sourceBlockIds` 仍为空。
5. parser 仍然承担：
   - `resolveOrdinalHeading`
   - “第几小节/章节/部分”
   - decimal heading 推断
   - heading 词面归一化匹配

问题：

1. 定位仍然不是“消费结构化 slot + snapshot 索引”的纯解析。
2. parser 仍然是业务规则中心。
3. text anchor 没有真实 range/source block 绑定。
4. media anchor 没有平台节点元数据支撑，只能靠 `contains` 猜类型。

结论：

必须重做成：

1. `DocumentStructureParser` 只做 XML/block/tree 解析。
2. `DocumentAnchorResolver` 只消费 `DocumentEditIntent.anchorSlots + DocumentStructureSnapshot.indexes`。
3. 无法唯一解析时返回 unresolved，不准猜。

### 4. `DocumentStructureSnapshotBuilder` 没有生成完整 snapshot，却把后续系统假定成完整 snapshot

文件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentStructureSnapshotBuilder.java`

现状：

1. 只抓 `outline`。
2. 然后按每个 top-level heading 循环 `fetchDocSection(..., "with-ids")`。
3. `rawFullXml` 被直接写成 `null`。
4. `rawFullMarkdown` 被直接写成 `null`。
5. `blockIndex` 只填了 section 内抓到的块，且 node 元数据极弱。

问题：

1. 这是“分段 section 抓取拼半成品 snapshot”，不是统一事实快照。
2. 大文档下按 top-level section 循环抓 `with-ids`，非常容易卡死。
3. 后续很多逻辑却又假设 snapshot 足够支撑 text verify / media verify / section compare。
4. 没有 after/before 可复用的稳定结构基线。

结论：

必须把 snapshot 体系重做成“轻量主快照 + 按需子快照”的明确模型，而不是现在这种不上不下的拼装：

1. 主快照负责：
   - revision
   - ordered block ids
   - heading tree
   - top-level section ranges
   - block type / parent / sibling / top-level ancestor
2. 子快照只在必要时抓 section/range 明细。
3. 不允许一会儿靠 outline，一会儿靠 section，一会儿靠 markdown 字符串，各自为政。

### 5. `DocumentPatchCompiler` 仍然以 `selector/matchedExcerpt` 为中心，不是以新模型为中心

文件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentPatchCompiler.java`

现状：

1. `compile(...)` 第一行仍是 `DocumentTargetSelector selector = toSelector(...)`。
2. 后续生成/改写 prompt 仍然大量吃 `selector.getMatchedExcerpt()`。
3. `toSelector(...)` 把：
   - section excerpt
   - block plainText
   - text matchedText
   全都再次压缩成旧 selector 模型。
4. explain/rewrite/insert 仍然以 excerpt 为主上下文。

问题：

1. 新模型进入 compiler 之后又被降解回旧世界对象。
2. `matchedExcerpt` 继续同时承担：
   - prompt source
   - preview
   - oldText
   - verify 辅助
3. 这和设计目标直接冲突。

结论：

你必须把 compiler 改成直接消费：

1. `DocumentEditIntent`
2. `ResolvedDocumentAnchor`
3. `DocumentEditStrategy`
4. `DocumentStructureSnapshot`
5. `PromptContext`

不允许再把 `toSelector()` 当主入口。

### 6. 现在的“文首插入 / 章节前插”虽然不再拼原标题，但 patch 计划仍然不可信

文件：

- `DocumentPatchCompiler.java`

现状：

1. `INSERT_METADATA_AT_DOCUMENT_HEAD`
2. `INSERT_SECTION_BEFORE_SECTION`

都改成了：

1. `APPEND`
2. 再 `BLOCK_MOVE_AFTER`

问题：

1. 计划里用 `"__new__"` 占位新 block id。
2. 但 append 后新增的未必是单一 block，也未必能直接移动整个逻辑 section。
3. 对多 block markdown section，`append -> extractFirstNewBlockId -> move one block` 很可能只移动首块，不是移动整节。
4. 也没有定义“多 block 结构如何作为一组移动”的运行态 contract。

结论：

这不是终态架构，只是从“错误的 block_replace 伪前插”升级到了“仍不完整的 move-placeholder 技巧”。你必须把：

1. section materialization
2. created block group tracking
3. grouped move / post-insert reorder

设计成显式运行态能力，而不是继续靠 `__new__` 字符串占位。

### 7. `DocumentTargetStateVerifier` 仍然严重依赖全文 contains 和 generated fragment

文件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/document/iteration/support/DocumentTargetStateVerifier.java`

现状：

1. `verifyTextReplaced()` 仍然通过 `normalize(md(after)).contains(normalize(generated))` 判断替换成功。
2. `verifyBlockInserted()` 通过 `containsMeaningfulFragment(md(after), generated)` 判断落盘。
3. `verifyMetadataAtHead()` 通过“首 heading 前的文本里是否包含 generated fragment”判断。
4. `md(snapshot)` 只是把 `blockIndex.values().plainText` 串起来，不是稳定结构视图。

问题：

1. 这依然是文本 contains verify，不是结构 verify。
2. after snapshot 里 block 顺序、block 类型、anchor 邻接关系都没有成为主判据。
3. 文本替换没有校验 anchor 绑定位置，只校验“新文本 somewhere 存在”。

结论：

必须重做成：

1. 基于 before/after snapshot 的结构 diff
2. 基于 anchor identity 的 target-state verify
3. 文本编辑至少绑定 source block / range
4. section 编辑至少绑定 heading block / body block set

### 8. 富媒体执行引擎已经接线，但执行闭环仍不合格

文件：

- `RichContentExecutionEngine.java`
- `RichContentTargetStateVerifier.java`
- `RichContentExecutionPlanner.java`

现状：

1. `RichContentExecutionEngine` 的确会调 handler registry 跑 step。
2. 但返回的 `beforeRevision/afterRevision` 仍然硬编码 `-1L`。
3. step 运行态产物只有 context map，没有形成标准化 runtime artifact contract。
4. `RichContentTargetStateVerifier` 只校验 `createdBlockIds` 是否出现在 after snapshot。
5. `RichContentExecutionPlanner` 还存在 `UPDATE_CAPTION -> lark_doc_str_replace` 这种再次退回文本 patch 语义的步骤。

问题：

1. 富媒体链路虽然不再是“完全不执行”，但 verify 仍然过弱。
2. revision 没有真实推进记录。
3. 位置正确性几乎没校验。
4. runtime context 还不是强类型。
5. caption 这类语义再次退回文本 patch，说明富媒体和文本链路边界没有彻底收口。

结论：

必须把富媒体执行链路升级成真正的 execution runtime：

1. typed `ExecutionRuntimeArtifact`
2. created block group / asset refs / revision transition
3. anchor-relative verify
4. 不允许 planner/step 再偷偷退回模糊文本替换

### 9. `LarkDocTool` 继续同时承担 CLI/OpenAPI/content codec/update fallback，已经是结构性风险源

文件：

- `im-collab-ai-assistant-skills/src/main/java/com/lark/imcollab/skills/lark/doc/LarkDocTool.java`

现状：

1. 一个类同时负责：
   - OpenAPI 创建文档
   - markdown 转 blocks
   - CLI fetch
   - CLI update
   - rich media update fallback
   - docx block codec
2. `fetchDocOutline/fetchDocSection/fetchDocFull/fetchDocByKeyword/updateByCommand` 全挂在一起。
3. `updateByCommand` 内还在做 command family 到 CLI mode 的多层兼容映射。

问题：

1. 抓取卡死、更新异常、markdown codec bug、CLI 兼容问题都堆在一个类里。
2. 无法对 snapshot read path 和 mutation write path 做差异化超时、重试、降级。
3. “CLI 升级导致全链路崩”这种事还会继续发生。

结论：

必须拆层：

1. `LarkDocReadGateway`
2. `LarkDocWriteGateway`
3. `LarkDocContentCodec`
4. `LarkDocOpenApiGateway`
5. `LarkDocCliGateway`

`DocumentIteration` 只能依赖抽象 gateway，不要直接抱住巨型 `LarkDocTool`。

---

## 二、这次整改的总目标

你这次不是修 bug，而是要把 `document-iteration` 收口成以下模型：

1. **Intent Layer**
2. **Snapshot Layer**
3. **Anchor Resolution Layer**
4. **Strategy Layer**
5. **Text Execution Layer / Rich Execution Layer**
6. **Target State Verify Layer**

并满足：

1. 不再以 `DocumentTargetSelector` 为主模型。
2. 不再以 `matchedExcerpt` 为核心字段。
3. 不再以 parser 中的 heading ordinal/词面规则作为主定位链。
4. 不再以全文 contains 为最终 verify。
5. 不再让 `LarkDocTool` 成为文档迭代内核的上帝对象。

---

## 三、允许你大刀阔斧重构的范围

允许直接重构或替换：

1. `DocumentEditIntentResolver`
2. `DocumentAnchorResolver`
3. `DocumentStructureParser`
4. `DocumentStructureSnapshotBuilder`
5. `DocumentPatchCompiler`
6. `DocumentPatchExecutor`
7. `DocumentTargetStateVerifier`
8. `RichContentExecutionPlanner`
9. `RichContentExecutionEngine`
10. `RichContentTargetStateVerifier`
11. `LarkDocTool` 相关 read/write adapter 拆分
12. `DocumentEditPlan`、`DocumentIterationPlanVO` 的 doc-agent 内部使用模型

允许删除或废弃：

1. `DocumentTargetLocator`
2. `DocumentAnchorIntentService`
3. `DocumentEditPlanBuilder`
4. 旧 selector/excerpt 驱动逻辑

前提：

1. `DocumentIterationExecutionService.execute(DocumentIterationRequest)` 入口保留。
2. 不要碰 Planner 的完成态任务编排策略。

---

## 四、必须落地的重构方案

### 1. 重做意图模型

要求：

1. `DocumentEditIntentResolver` 改成**单次结构化解析**。
2. 输出新模型，例如：
   - `intentType`
   - `semanticAction`
   - `anchorSpec`
   - `rewriteSpec`
   - `assetSpec`
   - `clarificationNeeded`
   - `riskHints`
3. 不再把 `targetRegion/targetSemantic/targetKeywords` 塞进 `Map<String, String>` 当弱类型槽位。
4. 无法明确时返回 unresolved/clarification，不准 fallback 成默认可执行动作。

### 2. 重做 snapshot 体系

要求：

1. 定义 `DocumentStructureSnapshot` 真正的一等模型。
2. 至少包含：
   - ordered block sequence
   - heading tree
   - block index
   - parent/sibling/top-level ancestor
   - section ranges
   - revision
   - lightweight raw payload references
3. 区分：
   - `BaseDocumentSnapshot`
   - `SectionSnapshot` / `RangeSnapshot`（按需）
4. 禁止主链路按所有 top-level section 循环抓 `with-ids`。
5. 给读取链路定义轻量模式和重模式，默认走轻量模式。

### 3. 重做 anchor resolution

要求：

1. anchor resolution 只能基于：
   - `DocumentEditIntent.anchorSpec`
   - `DocumentStructureSnapshot`
2. parser 不再解释“第一小节/第三章”。
3. 如果 LLM 想表达 ordinal/relative anchor，也必须先进入结构化 slot，例如：
   - `anchorKind=SECTION`
   - `matchMode=BY_HEADING_TITLE | BY_OUTLINE_PATH | BY_STRUCTURAL_ORDINAL`
   - `structuralOrdinal.scope=TOP_LEVEL_SECTION | SUB_SECTION`
4. 本地 resolver 只根据结构索引解析，不得再直接读中文 instruction 猜。
5. `TextAnchor` 必须带 source block ids / range context。
6. `MediaAnchor` 必须建立在 snapshot 的 media/table/embed 元数据上，不准只靠 `blockType.contains(...)`。

### 4. 编译器不准再回退成 selector 驱动

要求：

1. 删除 `DocumentPatchCompiler.toSelector()` 的主路径地位。
2. prompt 所需上下文改成单独的 `PromptContextAssembler` 负责。
3. plan 中显式放：
   - `anchorSnapshot`
   - `promptContext`
   - `compiledPatchPlan`
   - `executionPlan`
   - `expectedState`
4. `DocumentTargetSelector` 降级成临时兼容对象或直接删掉。

### 5. 重做文本编辑执行模型

要求：

1. 文本类操作只保留平台真实支持的最小能力：
   - `str_replace`
   - `block_insert_after`
   - `block_replace`
   - `block_delete`
   - `block_move_after`
   - `append`
2. `before insert` 一类平台不原生支持的语义，必须编译成显式的多步结构策略，不准再靠模糊块替换或单块占位技巧。
3. 对“append 后 move”这类多步操作，必须把“新创建 block group”的追踪建模清楚。
4. 不能只拿 append 结果的第一个 block id 当整组 section 的代理。

### 6. 重做富媒体执行模型

要求：

1. 建立 typed runtime artifact：
   - `CreatedBlockGroup`
   - `UploadedAssetRef`
   - `CreatedWhiteboardRef`
   - `CreatedTableRef`
   - `RevisionTransition`
2. `RichContentExecutionEngine` 必须输出真实：
   - `beforeRevision`
   - `afterRevision`
   - `created block groups`
   - `asset refs`
   - `step results`
3. `UPDATE_CAPTION` 这类语义如果仍需要文本操作，也必须作为显式桥接步骤，不得重新模糊化成“富媒体里偷偷 str_replace”。

### 7. 重做 verify 体系

要求：

1. 文本 verify 和富媒体 verify 都回到统一的 target-state 框架。
2. 主判据必须是 before/after snapshot 结构对比。
3. 仅允许把全文 contains 当辅助诊断，不得当最终成功标准。
4. 至少重做这些状态：
   - `EXPECT_TEXT_REPLACED`
   - `EXPECT_BLOCK_INSERTED_AFTER`
   - `EXPECT_METADATA_BLOCK_PRESENT_AT_HEAD`
   - `EXPECT_NEW_SECTION_BEFORE_TARGET_SECTION`
   - `EXPECT_SECTION_BODY_REMOVED`
   - `EXPECT_SECTION_REMOVED`
   - `EXPECT_IMAGE_NODE_PRESENT`
   - `EXPECT_TABLE_NODE_PRESENT`
   - `EXPECT_WHITEBOARD_NODE_PRESENT`

### 8. 拆分 `LarkDocTool`

要求：

1. 不要再让 `DocumentIteration` 直接深度依赖巨型 `LarkDocTool`。
2. 至少拆成：
   - `LarkDocReadGateway`
   - `LarkDocWriteGateway`
   - `LarkDocContentCodec`
3. CLI 和 OpenAPI 分别包在 gateway 下，不要继续把兼容分支泄漏给业务层。
4. snapshot read path 必须有更轻的 timeout/retry/config。
5. mutation path 必须能单独统计 revision 和 block creation metadata。

---

## 五、建议的目标代码结构

建议最终至少形成这些组件：

1. `DocumentEditIntentResolver`
2. `DocumentIntentSchema`
3. `DocumentPromptContextAssembler`
4. `DocumentSnapshotFacade`
5. `DocumentStructureSnapshotBuilder`
6. `DocumentAnchorResolver`
7. `DocumentEditStrategyPlanner`
8. `TextPatchCompiler`
9. `TextPatchExecutor`
10. `RichContentExecutionPlanner`
11. `RichContentExecutionEngine`
12. `DocumentTargetStateVerifier`
13. `LarkDocReadGateway`
14. `LarkDocWriteGateway`
15. `LarkDocContentCodec`

可以保留外观层：

1. `DefaultDocumentIterationExecutionService`

但它只能做 orchestration，不准再承担 plan model 转换细节。

---

## 六、实施顺序

按下面顺序直接改，不要边改边保留旧主路径：

1. 定义新的 intent/snapshot/anchor/plan/runtime/result 模型。
2. 拆 `LarkDocTool` 的 read/write/content codec。
3. 重做 `DocumentStructureSnapshotBuilder`。
4. 重做 `DocumentAnchorResolver`，切断对 parser 业务规则的依赖。
5. 重做 `DocumentPatchCompiler`，移除 `toSelector()` 主路径。
6. 重做 `DocumentTargetStateVerifier` 为结构态 verify。
7. 重做 `RichContentExecutionEngine`/`Verifier` runtime contract。
8. 最后清理 `DocumentTargetLocator` / `DocumentAnchorIntentService` / `DocumentEditPlanBuilder` / selector 残留。

---

## 七、明确禁止的做法

以下全部禁止：

1. 继续给 `DocumentStructureParser` 加“第一小节/第三章/章节/部分”的业务规则。
2. 继续保留 `matchedExcerpt` 作为核心字段。
3. 继续让 `toPlanVO()` 从 `selector` 取 `targetTitle/targetPreview`。
4. 继续用全文 contains 作为 verify 主依据。
5. 继续让 `LarkDocTool` 同时承担读取、写入、OpenAPI、CLI、codec、富媒体 fallback 所有职责。
6. 继续把多 block section 物化成 append 后靠一个新 block id 占位移动。
7. 继续保留“失败时默认猜一个最像的 heading/block”。
8. 继续做“只要 planningPhase 看起来对了就算完成”这种伪闭环。

---

## 八、验收标准

你改完后，必须同时满足：

1. `/api/planner/tasks/document-iteration` 的 Doc Agent 主链路不再依赖 `DocumentTargetSelector` 作为核心模型。
2. `DocumentStructureParser` 不再承担业务语义解释职责。
3. anchor resolution 无法唯一确定时，返回 unresolved/clarification/approval required，而不是猜。
4. snapshot builder 不再主路径循环抓所有 top-level section 的 `with-ids`。
5. `toPlanVO()` 的 target 信息来自 `resolvedAnchor/expectedState/strategy`，不是旧 selector。
6. 文本 verify 和富媒体 verify 都以结构态对比为主。
7. 富媒体 execution result 包含真实 revision transition 与 runtime artifacts。
8. `LarkDocTool` 的职责已经拆分，document iteration 不再直接耦合上帝对象。
9. “锚点定位不稳定、更新后 verify 漂移、抓取卡死”这三类问题在架构层有明确消除路径，而不是继续补 case。

---

## 九、输出要求

你完成改造时，请同时给出：

1. 最终架构说明
2. 关键类职责图
3. 删除了哪些旧主路径
4. 剩余未覆盖风险
5. 最少一组文本编辑链路测试
6. 最少一组富媒体插入链路测试
7. 最少一组大文档 snapshot 读取策略测试

一句话要求：

**把当前 `document-iteration` 从“新旧混杂、selector/excerpt 驱动、重抓取、弱 verify 的半成品”一次性收口成“结构快照驱动、强类型锚点、分层执行、目标态校验”的稳定 Doc Agent 内核。**
