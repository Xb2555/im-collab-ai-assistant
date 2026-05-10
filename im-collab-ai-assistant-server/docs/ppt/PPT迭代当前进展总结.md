# PPT 迭代当前进展总结

## 1. 当前已经完成的工作

### 1.1 正文重写/扩写改成“先生成替换文本，再执行修改”

已新增执行时正文生成组件：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/service/PresentationBodyRewriteService.java`

作用：

- 对 `REWRITE_ELEMENT / EXPAND_ELEMENT / SHORTEN_ELEMENT`，输入“原段落文本 + 用户指令 + 命中锚点”，生成真正可替换的正文。
- 对 `INSERT_AFTER_ELEMENT`，输入“原段落文本 + 用户指令 + 命中锚点”，生成真正要插入的新文本。

已加的保护：

- 生成结果不能为空。
- 生成结果不能退化成仅锚点短语，例如只剩“历史文化遗产”。
- 对重写/扩写/压缩，生成结果不能和原文完全相同。

### 1.2 PPT 修改执行链已接入执行时正文生成

已修改：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/service/PresentationIterationExecutionService.java`

已完成的能力：

- `EXPAND_ELEMENT / REWRITE_ELEMENT / SHORTEN_ELEMENT` 不再依赖解析阶段硬塞 `replacementText`。
- `INSERT_AFTER_ELEMENT` 已接入执行时生成新内容。
- Spring Bean 构造器问题已修：
  - 原报错：`No default constructor found`
  - 已通过显式 `@Autowired` 的三参构造修复，同时保留测试用双参构造。

### 1.3 PPT 意图解析已支持“引用段落后插入一小点”

已修改：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/support/PresentationEditIntentResolver.java`

当前已支持的 fallback 思路：

- `第一页历史文化遗产这一段写的详细一些`
  - 解析为 `EXPAND_ELEMENT + BODY + BY_QUOTED_TEXT`
- `在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点`
  - 解析为 `INSERT_AFTER_ELEMENT + BODY + BY_QUOTED_TEXT`

说明：

- 没有使用 `if equals` 这种硬编码匹配。
- 当前做法基于正则模式 + 结构化意图对象 + 执行时生成。

### 1.4 Planner 已补 completed task 的 PPT 调整测试

已修改：

- `im-collab-ai-assistant-planner/src/test/java/com/lark/imcollab/planner/supervisor/ReplanNodeServiceTest.java`

目标是覆盖：

- 已完成任务处于 `ASK_USER` 状态时，
- 用户直接发“在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点”，
- 应走 PPT 调整执行，而不是继续回复通用 `ASK_USER` 提示。

## 2. 刚刚新增完成的修复

### 2.1 修复“页内插一句”误走成页级 patch

用户实际反馈的问题：

- 说“在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点”
- 结果变成：
  - 像是插了一页
  - 左侧别的正文还被替换成“新的一小点”

原因已经定位：

- 之前 `INSERT_AFTER_ELEMENT` 在执行层走的是 `slides +replace-slide` 的页级 `block_insert` patch。
- 当前仓库已有 PPT 能力分析文档说明：Slides 不具备稳定的页内元素级插入能力，已有页加元素应走“Java 内存修改整页 XML，再整页替换”。

已修改：

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/service/PresentationIterationExecutionService.java`

当前实现已改为：

- 先命中目标 `shape`
- 在 Java DOM 内把新 `shape` 直接插到目标 `shape` 后面
- 再调用整页替换

不再使用之前的：

- `block_insert + index + block`

### 2.2 修复整页替换 CLI 命令写错

用户实际日志：

- `xml_presentation.slide patch`
- `Error: unknown flag: --as`

原因已定位：

- `LarkSlidesTool.replaceWholeSlide(...)` 用错了命令：
  - 写成了 `xml_presentation.slide patch --as ...`
- 本地 CLI 实际支持的是：
  - `slides xml_presentation.slide replace`
- 且该命令当前不接受 `--as`

已修改：

- `im-collab-ai-assistant-skills/src/main/java/com/lark/imcollab/skills/lark/slides/LarkSlidesTool.java`

当前已改成：

- `slides xml_presentation.slide replace --params ... --data ... --yes`

## 3. 目前正在干什么

当前正在做的是：

- 收尾验证“页内插一句”这条链路是否在真实代码路径上完全打通。
- 核对 `replaceWholeSlide` 修复后，相关测试是否全部通过。

## 4. 还没做完的事情

### 4.1 测试还没完全收口

当前测试状态分两部分：

1. 已通过：

- `PresentationIterationExecutionServiceTest`
- `PresentationEditIntentResolverTest`

2. 新一轮最小测试时，`LarkSlidesToolTest` 里暴露了两个旧测试失败：

- `createPresentationUsesCreateShortcutWithSlidesFile`
- `createPresentationReportsSlideIndexWhenAppendFails`

这两个失败不是这次 PPT 迭代插入修复直接引入的业务错误，更像是：

- `LarkSlidesToolTest` 里对 `createPresentation(...)` 的旧断言，和当前实现不一致
- 当前 `createPresentation(...)` 只做空 PPT 创建，不再带 `--slides`

也就是说：

- 现在 `replaceWholeSlide` 的命令修复已经生效
- 但整个 `LarkSlidesToolTest` 类里有历史测试与当前实现基线不一致，导致最小测试集整体红掉

### 4.2 Planner 侧还没有重新完整回归

虽然已经补了 planner 测试用例，但还没有在当前这轮修复后重新完成一次稳定回归，原因包括：

- 之前被本地 `.m2` 锁文件影响
- 后面又被 `LarkSlidesToolTest` 的历史失败拦住

## 5. 当前问题状况描述

### 5.1 已定位并修掉的问题

1. `第一页历史文化遗产这一段写的详细一些`

- 之前会把整段替换成“历史文化遗产”
- 原因：执行前没有基于原段生成真正替换文本
- 现已修成执行时生成

2. `在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点`

- 之前会误走页级 patch，导致位置和替换对象都不稳定
- 现已改成 Java 内存整页 XML 插入后整页替换

3. `replaceWholeSlide(...)` 命令错误

- 原因：错误使用 `patch --as`
- 已改成 `replace --params --data --yes`

### 5.2 当前仍未完全验证完成的问题

当前最大的未闭环点不是业务逻辑本身，而是验证链路：

- `LarkSlidesToolTest` 中存在与当前工具实现不一致的历史测试
- 需要先修这些旧测试，或者拆分只跑真正相关测试，才能把本轮修复完整跑绿

## 6. 本次相关代码文件

### 6.1 核心业务代码

- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/service/PresentationBodyRewriteService.java`
- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/service/PresentationIterationExecutionService.java`
- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/support/PresentationEditIntentResolver.java`
- `im-collab-ai-assistant-skills/src/main/java/com/lark/imcollab/skills/lark/slides/LarkSlidesTool.java`

### 6.2 已修改测试

- `im-collab-ai-assistant-harness/src/test/java/com/lark/imcollab/harness/presentation/service/PresentationIterationExecutionServiceTest.java`
- `im-collab-ai-assistant-harness/src/test/java/com/lark/imcollab/harness/presentation/service/StubPresentationBodyRewriteService.java`
- `im-collab-ai-assistant-harness/src/test/java/com/lark/imcollab/harness/presentation/support/PresentationEditIntentResolverTest.java`
- `im-collab-ai-assistant-planner/src/test/java/com/lark/imcollab/planner/supervisor/ReplanNodeServiceTest.java`
- `im-collab-ai-assistant-skills/src/test/java/com/lark/imcollab/skills/lark/slides/LarkSlidesToolTest.java`

## 7. 建议下一步

建议按这个顺序继续：

1. 修 `LarkSlidesToolTest` 里那两个与现实现状不一致的旧测试。
2. 重新跑最小测试：
   - `LarkSlidesToolTest`
   - `PresentationIterationExecutionServiceTest`
   - `PresentationEditIntentResolverTest`
   - `ReplanNodeServiceTest`
3. 本地重启应用后，直接用真实输入回归：
   - `第一页历史文化遗产这一段写的详细一些`
   - `在第一页的文旅融合创新，消费场景丰富多元后插入新的一小点`

## 8. 当前结论

当前业务修复方向已经明确，而且核心代码已经落下去：

- 正文扩写不再退化成锚点短语
- 页内“后插一句”不再走错误的页级 block patch
- 整页替换 CLI 命令也已经修正

目前没收口的是测试与验证，不是业务方向本身。
