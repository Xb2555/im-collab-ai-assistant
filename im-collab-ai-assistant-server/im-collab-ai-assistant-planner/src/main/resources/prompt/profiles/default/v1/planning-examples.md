Few-shot 示例（规划）：

示例1
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
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "WRITE_DOC",
          "tools": ["doc.write"],
          "input": "生成结构化需求文档",
          "context": "包含背景、目标、范围、验收标准"
        }
      ]
    }
  ]
}

示例2
输入：把下午讨论的用户画像功能整理成需求文档和评审PPT
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "沉淀用户画像需求文档",
      "description": "产出可评审的需求文档草稿",
      "type": "DOC",
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "tools": ["im.search", "doc.read"],
          "input": "提取下午讨论中与用户画像功能相关内容",
          "context": "聚焦目标、范围、约束"
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "WRITE_DOC",
          "tools": ["doc.write"],
          "input": "生成需求文档",
          "context": "突出业务目标、核心流程、验收标准"
        }
      ]
    },
    {
      "cardId": "card-002",
      "title": "生成评审PPT",
      "description": "面向评审会输出功能方案PPT",
      "type": "PPT",
      "dependsOn": ["card-001"],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-003",
          "parentCardId": "card-002",
          "taskType": "WRITE_SLIDES",
          "tools": ["slides.write"],
          "input": "基于需求文档生成评审PPT",
          "context": "覆盖背景、目标、方案、风险、排期"
        }
      ]
    }
  ]
}
