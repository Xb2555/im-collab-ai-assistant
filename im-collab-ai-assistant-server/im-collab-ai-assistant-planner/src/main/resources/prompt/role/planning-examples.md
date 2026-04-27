Few-shot 示例（规划）：

示例
输入：生成今天会议的需求文档
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "生成需求文档",
      "description": "基于今日会议内容生成需求文档",
      "type": "DOC",
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "tools": ["im.search", "doc.read"],
          "input": "检索今天会议相关消息和纪要",
          "context": "仅保留与需求相关内容"
        }
      ]
    }
  ]
}
