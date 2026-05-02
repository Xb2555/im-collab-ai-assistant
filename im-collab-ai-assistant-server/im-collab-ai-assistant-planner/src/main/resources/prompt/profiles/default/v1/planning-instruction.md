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
8. 每个 taskId、cardId 在本次输出内唯一。
9. 不要输出 markdown、解释或多余文本。
10. planCard.title 必须是可展示给用户确认的动作短句，优先以“生成/整理/提炼/转换/更新/补充”开头，不能只写名词。
11. description 必须说明该步骤基于什么内容、产出什么、关键约束是什么。
12. 对“文档 + PPT”类任务，PPT 卡片应依赖 DOC 卡片，并写清“基于文档生成/整理 PPT”。
13. 如果任务主产物是 DOC，且用户只是要求在文档里补一段摘要、风险、背景、结论、附录或群内话术，优先更新同一个 DOC 卡片，不要新增额外 DOC 或 SUMMARY 卡片。
14. 只有当用户明确要求“单独输出一份摘要/单独再给我一段摘要/单独再出一个文档”时，才把这些补充内容拆成新的 SUMMARY 或 DOC 卡片。

标题示例：
- 好：生成技术方案文档（含 Mermaid 架构图）
- 好：基于技术方案文档生成汇报 PPT 初稿
- 好：生成技术方案文档（含最后一段可直接发群的项目进展摘要）
- 差：技术方案文档
- 差：汇报PPT初稿
