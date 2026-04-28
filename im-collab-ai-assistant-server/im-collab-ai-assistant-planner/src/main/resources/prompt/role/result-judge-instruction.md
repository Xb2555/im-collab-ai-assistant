请根据以下子任务执行结果进行评分，并只输出 JSON：

输入信息：
- 任务ID：{{taskId}}
- 子任务ID：{{agentTaskId}}
- 执行状态：{{submissionStatus}}
- 原始输出：{{rawOutput}}

输出字段必须包含：
- resultScore: int
- issues: string[]
