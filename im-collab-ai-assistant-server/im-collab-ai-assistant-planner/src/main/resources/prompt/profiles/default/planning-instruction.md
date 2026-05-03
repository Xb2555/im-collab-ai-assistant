你正在处理一个任务规划请求，请严格按以下输入生成结构化计划：

输入信息：
- 用户原始指令：{{rawInstruction}}
- 补充上下文：{{context}}
- 历史澄清回答：{{clarificationAnswers}}
- 任务级对话记忆：{{conversationMemory}}

执行要求：
1. 输出必须是有效 JSON。
2. 顶层结构必须为 {"planCards":[...]}。
3. 每个 planCard 至少包含 1 个 agentTaskPlanCard。
4. planCard.type 只能是 DOC、PPT、SUMMARY。
5. taskType 只能是 WRITE_DOC、WRITE_SLIDES、GENERATE_SUMMARY。
6. 只写当前系统能执行的计划，不要输出白板、归档、发送 IM、搜索、任意外部工具步骤。
7. Mermaid 只能写进 DOC 卡片的 description 或 input 中。
8. 不要新增用户没有要求的交付物。
9. “计划/三步以内/先不要执行/大纲/初稿”通常是对 Planner 的约束，不是最终产物内容；不要生成“XX 文档计划”这类计划的计划，应该规划真实可执行的 DOC/PPT/SUMMARY 产物。
10. 如果用户要项目复盘、总结、报告、给老板看的材料，但输入里没有项目事实、选中消息、文档链接或可拉取的消息范围，应该等待澄清结果，不要硬编 planCards。
11. 每个 taskId、cardId 在本次输出内唯一。
12. 不要输出 markdown、解释或多余文本。
13. planCard.title 必须是可展示给用户确认的动作短句，优先以“生成/整理/提炼/转换/更新/补充”开头，不能只写名词。
14. description 必须说明该步骤基于什么内容、产出什么、关键约束是什么。
15. 对“文档 + PPT”类任务，PPT 卡片应依赖 DOC 卡片，并写清“基于文档生成/整理 PPT”。
