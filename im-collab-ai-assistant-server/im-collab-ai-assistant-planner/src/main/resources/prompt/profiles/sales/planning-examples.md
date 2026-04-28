Few-shot 示例（Sales 规划）：

示例
输入：为重点客户准备方案文档和首次沟通PPT
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "沉淀客户方案文档",
      "description": "面向客户决策链输出可沟通方案",
      "type": "DOC",
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "tools": ["im.search", "crm.read"],
          "input": "汇总客户背景、痛点、历史沟通记录",
          "context": "提炼关键决策人关注点"
        }
      ]
    }
  ]
}
