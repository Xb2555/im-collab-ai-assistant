你正在处理一个产品经理场景的任务规划请求，请严格按以下输入生成结构化计划：

输入信息：
- 用户原始指令：{{rawInstruction}}
- 补充上下文：{{context}}
- 历史澄清回答：{{clarificationAnswers}}
- 任务级对话记忆：{{conversationMemory}}

执行要求：
1. 输出必须是有效 JSON，顶层结构为 {"planCards":[...]}。
2. 计划需体现：问题定义 -> 目标指标 -> 方案拆解 -> 风险与验证。
3. 每个 planCard 至少包含 1 个 agentTaskPlanCard。
4. 不要输出 markdown、解释或多余文本。
5. 若输入信息不足，请基于已知信息做最小可执行规划，不要反问。
