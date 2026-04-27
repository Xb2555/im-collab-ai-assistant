# Prompt Versioning

## 目录结构

- `prompt/role/*.md`：角色级基础模板（兜底）
- `prompt/profiles/<profile>/<version>/*.md`：按档位与版本维护模板
- `prompt/legacy/*.md`：历史模板兼容目录（可选）

推荐每个 Agent 使用双模板：
- `*-system.md`：稳定角色与行为边界（systemPrompt）
- `*-instruction.md`：任务输入、格式约束、few-shot（instruction）

P2 约定：每个 Agent 最少三层模板：
- `*-system.md`：角色定义与不可变约束
- `*-instruction.md`：当前任务输入、输出格式、执行规则
- `*-examples.md`：few-shot 示例

P1 实现约定：
- 动态 prompt 注入统一走 `ModelInterceptor`（`AgentPromptInterceptor`）。
- `ReactAgent` Bean 中保留 `systemPrompt` 作为默认兜底；运行时按请求上下文重写。
- 业务层禁止直接调用 `setSystemPrompt` / `setInstruction` 修改单例 Agent。

## 解析顺序（从高到低）

1. `prompt/profiles/{profile}/{version}/{file}`
2. `prompt/profiles/{profile}/{file}`
3. `prompt/profiles/{fallbackProfile}/{fallbackVersion}/{file}`
4. `prompt/profiles/{fallbackProfile}/{file}`
5. `prompt/role/{file}`
6. `prompt/legacy/{file}`
7. `prompt/{file}`

## 配置项

`application.yml`:

```yaml
planner:
  prompt:
    profile: default
    version: v1
    fallback-profile: default
    fallback-version: v1
```

## 会话级覆盖

通过 `WorkspaceContext` 传参：

- `promptProfile`
- `promptVersion`

会写入 `PlanTaskSession`，并在运行时动态应用到对应 agent 的 `system/instruction`。
