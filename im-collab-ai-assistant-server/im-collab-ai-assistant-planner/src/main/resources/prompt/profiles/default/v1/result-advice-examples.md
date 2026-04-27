Few-shot 示例（裁决）：

示例1
输入：resultScore=85，问题较少。
输出：
{
  "verdict": "PASS",
  "suggestions": [
    "可在最终交付前补充一段风险监控说明，提高可维护性。"
  ]
}

示例2
输入：resultScore=65，存在结构与信息缺口。
输出：
{
  "verdict": "RETRY",
  "suggestions": [
    "补充关键风险的分级与触发条件。",
    "将结论改为“结论+行动项+截止时间”三段结构。"
  ]
}
