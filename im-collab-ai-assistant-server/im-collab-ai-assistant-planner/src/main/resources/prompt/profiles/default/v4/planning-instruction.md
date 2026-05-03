你正在处理一个任务规划请求，请严格按以下输入生成结构化计划：

输入信息：
- 用户原始指令：{{rawInstruction}}
- 补充上下文：{{context}}
- 历史澄清回答：{{clarificationAnswers}}
- 任务级对话记忆：{{conversationMemory}}

执行要求：
1. 输出必须是有效 JSON。
2. 顶层结构必须为 {"planCards":[...]}。
3. planCard.type 只能是 DOC、PPT、SUMMARY。
4. taskType 只能是 WRITE_DOC、WRITE_SLIDES、GENERATE_SUMMARY。
5. 只写当前系统能执行的计划，不要输出白板、归档、发送 IM、搜索、任意外部工具步骤。
6. Mermaid 只能写进 DOC 卡片的 description 或 input 中。
7. 不要新增用户没有要求的交付物。
8. “总结文档/总结成文档/生成总结文档”必须按 DOC 规划；“一段摘要/可直接发到群里的摘要/话术”才按 SUMMARY 规划。
9. 不要声称“选中的 N 条消息”或具体讨论主题，除非补充上下文里真的有 `Selected messages` 内容。
10. “计划/三步以内/先不要执行/大纲/初稿”通常是对 Planner 的约束，不是最终产物内容；不要生成“XX 文档计划”这类计划的计划，应该规划真实可执行的 DOC/PPT/SUMMARY 产物。
11. 如果用户要项目复盘、总结、报告、给老板看的材料，但输入里没有项目事实、选中消息、文档链接或可拉取的消息范围，应该等待澄清结果，不要硬编 planCards。
12. 每个 taskId、cardId 在本次输出内唯一。
13. 不要输出 markdown、解释或多余文本。
14. planCard.title 必须是可展示给用户确认的动作短句，优先以“生成/整理/提炼/转换/更新/补充”开头，不能只写名词。
15. description 必须说明该步骤基于什么内容、产出什么、关键约束是什么。
16. 对“文档 + PPT”类任务，PPT 卡片应依赖 DOC 卡片，并写清“基于文档生成/整理 PPT”。

标题示例：
- 好：生成技术方案文档（含 Mermaid 架构图）
- 好：基于技术方案文档生成汇报 PPT 初稿
- 差：技术方案文档
- 差：汇报PPT初稿
