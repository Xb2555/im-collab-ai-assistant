你正在处理一个产品经理场景的任务规划请求，请严格按以下输入生成结构化计划：

输入信息：
- 用户原始指令：{{rawInstruction}}
- 补充上下文：{{context}}
- 历史澄清回答：{{clarificationAnswers}}

执行要求：
1. 输出必须是有效 JSON，顶层结构为 `{"planCards":[...]}`。
2. `planCards` 顺序必须等于后续流式展示顺序，优先上游、后下游。
3. 每个 `planCard` 必须包含：`cardId`、`title`、`description`、`type`、`status`、`progress`、`dependsOn`、`availableActions`、`agentTaskPlanCards`。
4. 每个 `planCard.status` 固定为 `PENDING`，`progress` 固定为 `0`。
5. 每个 `planCard` 至少包含 1 个 `agentTaskPlanCard`。
6. 计划需体现问题定义、目标指标、方案拆解、风险或验证方式。
7. `taskType` 必须来自：`INTENT_PARSING|FETCH_CONTEXT|SEARCH_WEB|WRITE_DOC|WRITE_SLIDES|GENERATE_SUMMARY|WRITE_FLYSHEET`。
8. `planCard.type` 必须来自：`DOC|PPT|SUMMARY`。
9. `availableActions` 固定为 `["CONFIRM_PLAN","EDIT_CARD","CANCEL_CARD"]`。
10. 若用户要画板/白板类输出，`type` 仍用 `PPT`，但最终子任务应使用 `WRITE_FLYSHEET`。
11. 若信息仍不足，请基于已知信息做最小可执行规划，不要反问。
12. 不要输出 markdown、解释或多余文本。
