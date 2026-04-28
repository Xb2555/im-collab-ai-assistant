Few-shot 示例（v4 规划）：

示例1
输入：把今天群里讨论的门店巡检助手整理成需求文档，并给老板准备评审PPT，周四中午前完成。
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "生成需求文档",
      "description": "基于今天群聊讨论整理门店巡检助手需求文档，突出目标、范围、流程和验收标准，周四中午前交付",
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
          "input": "提取今天群聊中与门店巡检助手相关的需求、问题和结论",
          "context": "只保留与产品目标、使用流程、约束条件有关的信息"
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "WRITE_DOC",
          "status": "PENDING",
          "tools": ["doc.write"],
          "input": "生成可评审的需求文档",
          "context": "面向老板和项目组，结构包含背景、目标、核心流程、功能范围、验收标准"
        }
      ]
    },
    {
      "cardId": "card-002",
      "title": "生成评审PPT",
      "description": "基于需求文档生成一份给老板评审的汇报PPT，突出问题、方案、风险和下一步，周四中午前交付",
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
          "context": "基于 card-001 的产物继续生成，面向老板汇报，覆盖背景、目标、方案、风险、推进计划"
        }
      ]
    }
  ]
}

示例2
输入：把本周客户拜访纪要整理成一页总结，给销售团队同步。
输出：
{
  "planCards": [
    {
      "cardId": "card-001",
      "title": "生成客户拜访总结",
      "description": "整理本周客户拜访纪要并生成一页摘要，面向销售团队同步重点反馈、机会和风险",
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
          "input": "收集本周客户拜访纪要和相关聊天记录",
          "context": "聚焦客户诉求、成交机会、阻塞问题和后续动作"
        },
        {
          "taskId": "task-002",
          "parentCardId": "card-001",
          "taskType": "GENERATE_SUMMARY",
          "status": "PENDING",
          "tools": ["doc.write"],
          "input": "生成一页团队同步摘要",
          "context": "按重点反馈、机会、风险、建议动作四部分组织内容"
        }
      ]
    }
  ]
}
