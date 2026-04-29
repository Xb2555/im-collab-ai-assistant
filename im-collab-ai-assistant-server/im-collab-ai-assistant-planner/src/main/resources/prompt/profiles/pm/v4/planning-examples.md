Few-shot 示例（PM v4 规划）：

示例1
输入：把今天评审会上关于企业知识库问答助手的讨论整理成需求文档，并生成老板评审PPT，周五上午前完成。
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "沉淀需求文档",
      "description": "基于今天评审会讨论整理企业知识库问答助手需求文档，明确问题、目标、范围、流程和验收标准，周五上午前交付",
      "type": "DOC",
      "status": "PENDING",
      "progress": 0,
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "status": "PENDING",
          "tools": ["im.search", "doc.read"],
          "input": "提取评审会上关于知识库问答助手的问题、目标、方案和约束",
          "context": "聚焦痛点、目标用户、能力边界、验收标准和关键风险"
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "WRITE_DOC",
          "status": "PENDING",
          "tools": ["doc.write"],
          "input": "生成可评审的需求文档",
          "context": "面向老板和研发评审，结构包含背景、目标、方案、风险、验收标准、下一步"
        }
      ]
    },
    {
      "cardId": "card-002",
      "title": "生成评审PPT",
      "description": "基于需求文档生成老板评审PPT，突出问题、目标、方案、风险和推进计划，周五上午前交付",
      "type": "PPT",
      "status": "PENDING",
      "progress": 0,
      "dependsOn": ["card-001"],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-003",
          "parentCardId": "card-002",
          "taskType": "WRITE_SLIDES",
          "status": "PENDING",
          "tools": ["slides.write"],
          "input": "根据需求文档生成评审PPT",
          "context": "基于 card-001 的产物继续生成，面向老板评审，突出目标指标、方案亮点、风险和下一步"
        }
      ]
    }
  ]
}

示例2
输入：把本月留存下滑的讨论整理成复盘摘要，给产品团队同步。
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "生成留存复盘摘要",
      "description": "整理本月留存下滑相关讨论并生成团队复盘摘要，突出问题、指标变化、假设和后续验证方向",
      "type": "SUMMARY",
      "status": "PENDING",
      "progress": 0,
      "dependsOn": [],
      "availableActions": ["CONFIRM_PLAN", "EDIT_CARD", "CANCEL_CARD"],
      "agentTaskPlanCards": [
        {
          "taskId": "task-001",
          "parentCardId": "card-001",
          "taskType": "FETCH_CONTEXT",
          "status": "PENDING",
          "tools": ["im.search", "doc.read"],
          "input": "汇总本月留存下滑相关讨论、数据口径和结论",
          "context": "聚焦指标变化、核心原因假设、已验证结论和待确认风险"
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "GENERATE_SUMMARY",
          "status": "PENDING",
          "tools": ["doc.write"],
          "input": "生成团队同步复盘摘要",
          "context": "按问题、指标、假设、风险、下一步验证五部分组织内容"
        }
      ]
    }
  ]
}
