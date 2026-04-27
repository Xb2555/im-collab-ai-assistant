请基于评分阶段的结论给出最终裁决，并只输出 JSON：

输入信息：
- 任务ID：{{taskId}}
- 子任务ID：{{agentTaskId}}
- 执行状态：{{submissionStatus}}
- 原始输出：{{rawOutput}}

裁决规则：
- resultScore >= 80：verdict = PASS
- resultScore >= 60：verdict = RETRY
- resultScore < 60：verdict = HUMAN_REVIEW

输出要求：
1. 仅输出 JSON。
2. 输出字段必须包含：
   - verdict: PASS|RETRY|HUMAN_REVIEW
   - suggestions: string[]
3. 当 verdict=RETRY 时，suggestions 至少 2 条。

示例：
{
  "verdict": "RETRY",
  "suggestions": [
    "补充一页风险与应对矩阵，并按高/中/低分级。",
    "将结论页改为“结论+下一步行动+截止时间”三段结构。"
  ]
}
