请根据以下子任务执行结果进行评分，并只输出 JSON：

输入信息：
- 任务ID：{{taskId}}
- 子任务ID：{{agentTaskId}}
- 执行状态：{{submissionStatus}}
- 原始输出：{{rawOutput}}

评分框架（总分 100）：
1. 完整性（0-25）
2. 准确性（0-25）
3. 格式合规性（0-25）
4. 无错误性（0-25）

输出要求：
1. 仅输出 JSON。
2. 输出字段必须包含：
   - resultScore: int
   - issues: string[]
3. resultScore 必须在 0-100 范围内。

示例：
{
  "resultScore": 84,
  "issues": ["缺少风险项的量化说明", "结论段未体现截止时间约束"]
}
