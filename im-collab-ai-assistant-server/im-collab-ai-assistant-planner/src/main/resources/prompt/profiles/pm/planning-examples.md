Few-shot 示例（PM 规划）：

示例
输入：把本周用户留存分析沉淀成复盘文档并准备评审材料
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "沉淀留存复盘文档",
      "description": "产出可评审的留存分析与结论",
      "type": "DOC",
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "tools": ["im.search", "doc.read"],
          "input": "汇总留存相关数据与讨论记录",
          "context": "聚焦指标口径、阶段结论、风险项"
        }
      ]
    }
  ]
}
