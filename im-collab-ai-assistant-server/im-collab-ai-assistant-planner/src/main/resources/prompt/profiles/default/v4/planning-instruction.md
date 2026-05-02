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
10. 每个 taskId、cardId 在本次输出内唯一。
11. 不要输出 markdown、解释或多余文本。
12. planCard.title 必须是可展示给用户确认的动作短句，优先以“生成/整理/提炼/转换/更新/补充”开头，不能只写名词。
13. description 必须说明该步骤基于什么内容、产出什么、关键约束是什么。
14. 对“文档 + PPT”类任务，PPT 卡片应依赖 DOC 卡片，并写清“基于文档生成/整理 PPT”。

标题示例：
- 好：生成技术方案文档（含 Mermaid 架构图）
- 好：基于技术方案文档生成汇报 PPT 初稿
- 差：技术方案文档
- 差：汇报PPT初稿
