你正在处理一个任务规划请求，请严格按以下输入生成结构化计划：

输入信息：
- 用户原始指令：{{rawInstruction}}
- 补充上下文：{{context}}
- 历史澄清回答：{{clarificationAnswers}}

执行要求：
1. 输出必须是有效 JSON。
2. 顶层结构必须为 {"planCards":[...]}。
3. 每个 planCard 至少包含 1 个 agentTaskPlanCard。
4. taskType 必须来自：
   INTENT_PARSING|FETCH_CONTEXT|SEARCH_WEB|WRITE_DOC|WRITE_SLIDES|GENERATE_SUMMARY|WRITE_FLYSHEET
5. planCard.type 必须来自：DOC|PPT|SUMMARY。
6. 每个 taskId、cardId 在本次输出内唯一。
7. 不要输出 markdown、解释或多余文本。
8. 若输入信息不足，请基于已知信息做最小可执行规划，不要反问。

少样本示例（片段）：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "沉淀周报文档",
      "description": "生成面向管理层的周报文档草稿",
      "type": "DOC",
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "tools": ["im.search", "doc.read"],
          "input": "抓取本周项目消息与文档更新",
          "context": "只保留与目标项目相关信息"
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "WRITE_DOC",
          "tools": ["doc.write"],
          "input": "按管理层阅读习惯生成周报",
          "context": "突出进展、风险、下周计划"
        }
      ]
    }
  ]
}
