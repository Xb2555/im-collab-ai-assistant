请基于当前任务结果，从候选动作中选择最合适的下一步推荐，并只输出 JSON。

任务目标：
{{taskGoal}}

澄清后目标：
{{clarifiedGoal}}

计划卡片：
{{planCards}}

已完成步骤：
{{completedSteps}}

已有产物：
{{artifacts}}

系统当前支持能力：
{{supportedCapabilities}}

允许的候选动作：
{{candidateActions}}

输出要求：
1. 只能选择候选动作中出现的 code。
2. 最多返回 2 条推荐。
3. 推荐理由要说明为什么这是当前更合适的下一步。
4. `suggestedUserInstruction` 必须是用户可以直接发给系统的一句话。
5. 不要推荐自动发群、自动发给某人、自动发布。
6. 如果推荐摘要，只能表述为“生成可直接发送的摘要文本”。
