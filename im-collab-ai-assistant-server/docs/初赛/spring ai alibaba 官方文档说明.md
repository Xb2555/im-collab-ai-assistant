# Spring AI Alibaba 官方文档说明

用途：这份文档不是“介绍 Spring AI Alibaba 是什么”，而是给 Claude/Codex 这类代码模型当实现参考的。重点只放：

- 这个小节能干什么
- 应该优先用哪些类
- 最小实现路径是什么
- 写代码时容易踩的点是什么

范围：基于 Spring AI Alibaba 官方文档中 `Agent Framework` 与 `Graph Core` 章节及其可见子页整理。

---

## 1. 先给 Claude 的总规则

如果需求是“做一个能调用模型、工具、多轮记忆、可插 Hook 的 Agent”，优先用：

- `ReactAgent`
- `ChatModel`
- `ToolCallback` / `FunctionToolCallback`
- `RunnableConfig`
- `MemorySaver`
- `ModelHook` / `AgentHook`
- `ModelInterceptor` / `ToolInterceptor`

如果需求是“做一个可编排、可中断、可恢复、可持久化的工作流”，优先用：

- `StateGraph`
- `CompiledGraph`
- `OverAllState`
- `KeyStrategyFactory`
- `AppendStrategy`
- `ReplaceStrategy`
- `CompileConfig`
- `RunnableConfig`
- `MemorySaver` / `RedisSaver`

简单判断：

- 偏“智能体推理 + 工具调用”用 `ReactAgent`
- 偏“确定性工作流编排”用 `StateGraph`
- 需要两者结合时：`ReactAgent` 作为 Graph 节点，或者 Graph 作为 Agent 的底层流程

---

## 2. Agent Framework

## 2.1 Agents

### 核心用途

构建生产级 ReAct Agent。它会循环做三件事：

- 看上下文
- 决定是否调用工具
- 根据工具结果继续推理，直到给出最终回答或达到停止条件

### 核心类

- `com.alibaba.cloud.ai.graph.agent.ReactAgent`
- `org.springframework.ai.chat.model.ChatModel`
- `com.alibaba.cloud.ai.graph.RunnableConfig`
- `com.alibaba.cloud.ai.graph.OverAllState`
- `org.springframework.ai.chat.messages.UserMessage`
- `org.springframework.ai.chat.messages.AssistantMessage`

### 最小实现

```java
ReactAgent agent = ReactAgent.builder()
    .name("my_agent")
    .model(chatModel)
    .systemPrompt("你是一个有帮助的助手")
    .build();

AssistantMessage reply = agent.call("帮我写一首诗");
```

### 怎么干

1. 先准备 `ChatModel`
2. 用 `ReactAgent.builder()` 组装 Agent
3. 需要工具就加 `.tools(...)`
4. 需要记忆就加 `.saver(...)`
5. 需要拿完整状态就用 `invoke()`，只要最终文本就用 `call()`

### 关键调用方式

- `call(String | UserMessage | List<Message>)`
  - 只关心最终回复时用
- `invoke(...)`
  - 需要读状态、调试中间产物、拿 `messages` 或自定义状态时用
- `stream(...)`
  - 需要流式输出时用

### Claude 写代码时的建议

- 用户要“回答文本”时优先 `call`
- 用户要“完整执行轨迹/状态”时优先 `invoke`
- 用户要“多轮会话隔离”时必须传 `RunnableConfig.threadId`

---

## 2.2 Models

### 核心用途

配置底层聊天模型，给 Agent 提供推理能力。文档示例重点是 `DashScopeChatModel`，但抽象层是 `ChatModel`。

### 核心类

- `org.springframework.ai.chat.model.ChatModel`
- `com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel`
- `com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions`
- `org.springframework.ai.chat.prompt.Prompt`
- `org.springframework.ai.chat.model.ChatResponse`

### 最小实现

```java
ChatModel chatModel = DashScopeChatModel.builder()
    .dashScopeApi(dashScopeApi)
    .defaultOptions(DashScopeChatOptions.builder()
        .withModel("qwen-plus")
        .withTemperature(0.7)
        .withMaxToken(2000)
        .withTopP(0.9)
        .build())
    .build();
```

### 怎么干

- 静态默认参数放 `defaultOptions`
- 单次调用临时覆盖参数，放到 `Prompt` 的 runtime options 里
- 要流式就调用 `chatModel.stream(prompt)`

### Claude 写代码时的建议

- 业务接口里不要把供应商模型类到处传，尽量上层只依赖 `ChatModel`
- 想降低幻觉和波动，先把 `temperature` 压低
- 要长输出时记得显式设置 `maxToken`

---

## 2.3 Messages

### 核心用途

统一表示模型输入输出。消息是 Agent 与 Model 的通用载体。

### 核心类

- `SystemMessage`
- `UserMessage`
- `AssistantMessage`
- `Prompt`
- `Message`
- `Media`

### 能干什么

- 表示 system/user/assistant/tool 消息
- 组织多轮对话
- 传多模态输入：图片、音频、视频、文件
- 携带 metadata

### 最小实现

```java
List<Message> messages = List.of(
    new SystemMessage("你是一个 Java 专家"),
    new UserMessage("解释一下线程池")
);
Prompt prompt = new Prompt(messages);
ChatResponse response = chatModel.call(prompt);
```

### 多模态写法

```java
UserMessage msg = UserMessage.builder()
    .text("描述这张图片")
    .media(Media.builder()
        .mimeType(MimeTypeUtils.IMAGE_JPEG)
        .data(new URL("https://example.com/a.jpg"))
        .build())
    .build();
```

### Claude 写代码时的建议

- 简单单轮请求可直接 `chatModel.call("...")`
- 只要涉及 system prompt、多轮历史、多模态，就显式构造 `Message`
- 要做消息改写时优先用 builder / mutate，不要手写复制逻辑

---

## 2.4 Tools

### 核心用途

给 Agent 外部行动能力。模型负责决定“要不要调用工具”，框架负责把工具真正执行掉。

### 核心类

- `ToolCallback`
- `FunctionToolCallback`
- `MethodToolCallback`
- `ToolDefinition`
- `ToolCallingManager`
- `ToolContext`
- `@Tool`

### 提供工具的几种方式

#### 1. 直接传 `tools(...)`

适合工具少、定义清楚的场景。

```java
ToolCallback weatherTool = FunctionToolCallback.builder("get_weather", new WeatherFunction())
    .description("Get weather for a city")
    .inputType(WeatherRequest.class)
    .build();

ReactAgent agent = ReactAgent.builder()
    .name("agent")
    .model(chatModel)
    .tools(weatherTool)
    .build();
```

#### 2. `methodTools(...)` + `@Tool`

适合把工具按类组织。

```java
public class CalculatorTools {
    @Tool(description = "Add two numbers")
    public String add(int a, int b) {
        return String.valueOf(a + b);
    }
}
```

#### 3. `toolNames(...)` + resolver

适合工具定义与工具使用分离。

#### 4. provider 方式

适合从不同来源装配工具集。

### `ToolContext` 能做什么

工具里最重要的隐藏参数。模型看不到它，但代码能拿到：

- 当前状态 `state`
- 当前配置 `config`
- 长期存储 `store`
- 上下文数据 `context`
- 当前工具调用 ID

### 常见模式

#### 读取短期状态

```java
public class ConversationSummaryTool implements BiFunction<String, ToolContext, String> {
    @Override
    public String apply(String input, ToolContext toolContext) {
        OverAllState state = (OverAllState) toolContext.getContext().get("state");
        RunnableConfig config = (RunnableConfig) toolContext.getContext().get("config");
        return "ok";
    }
}
```

#### 读取业务上下文

从 `RunnableConfig.metadata(...)` 或 `ToolContext` 取用户 ID、租户、会话信息。

#### 配合长期记忆

通过 `config.store()` 或 `ToolContext` 拿到 `Store` / `MemoryStore` 查用户资料。

### Claude 写代码时的建议

- 复杂参数不要用裸 `String`，优先 `inputType(YourRequest.class)`
- 工具描述要写具体，不然模型不会选对工具
- 工具返回值尽量结构化，避免只返大段自然语言
- 需要读状态/记忆时，让工具签名带 `ToolContext`

---

## 2.5 Memory（短期记忆）

### 核心用途

让 Agent 在同一线程内记住上下文，本质上是 Graph 状态的持久化。

### 核心类

- `MemorySaver`
- `RunnableConfig`
- `OverAllState`
- `ModelHook`
- `ToolContext`

### 怎么干

- `threadId` 表示一个会话线程
- `messages` 通常是默认短期记忆键
- 有 saver 时，Agent 每轮会自动读写状态

### 最小实现

```java
ReactAgent agent = ReactAgent.builder()
    .name("agent")
    .model(chatModel)
    .saver(new MemorySaver())
    .build();

RunnableConfig config = RunnableConfig.builder()
    .threadId("user-session-1")
    .build();

agent.call("我叫 Bob", config);
agent.call("我叫什么？", config);
```

### 访问短期记忆

#### 在工具里读

通过 `ToolContext` 拿当前状态。

#### 在 Hook 里读写

通过 `ModelHook.beforeModel/afterModel` 改 `messages` 或其他状态键。

### 文档强调的常见处理策略

- 修剪消息
- 删除消息
- 摘要旧消息
- 自定义过滤策略

### Claude 写代码时的建议

- 任何“记住上下文”的需求，都先检查有没有稳定的 `threadId`
- 对长对话，不要无限堆消息；要加 summary / trim 机制

---

## 2.6 Memory（长期记忆）

### 核心用途

跨会话保存用户画像、偏好、规则、业务档案。短期记忆靠 `Saver`，长期记忆靠 `Store`。

### 核心类

- `com.alibaba.cloud.ai.graph.store.Store`
- `com.alibaba.cloud.ai.graph.store.StoreItem`
- `com.alibaba.cloud.ai.graph.store.stores.MemoryStore`
- `RunnableConfig.store(...)`

### 数据模型

- `namespace`
  - 类似目录/分组
- `key`
  - 唯一主键
- `value`
  - JSON 文档

### 最小实现

```java
MemoryStore store = new MemoryStore();
StoreItem item = StoreItem.of(List.of("users"), "user_123", Map.of(
    "name", "张三",
    "language", "中文"
));
store.putItem(item);
```

### 怎么让 Agent 用上长期记忆

#### 方式 1：作为工具显式读写

- 读：`getUserInfo`
- 写：`saveMemory`

#### 方式 2：在 Hook 中自动注入

在 `beforeModel` 里从 store 取用户画像，转成 `SystemMessage` 注入当前请求。

#### 方式 3：短期 + 长期结合

- 短期：当前会话消息
- 长期：跨会话用户资料
- Hook 在每轮模型调用前把长期记忆拼进上下文

### Claude 写代码时的建议

- 用户画像、偏好、规则放长期记忆，不要塞进每轮消息硬编码
- 会话历史放短期记忆，不要反复写到 Store
- 长期记忆注入优先在 Hook 层做，避免业务代码四处拼 prompt

---

## 2.7 Context Engineering

### 核心用途

控制 Agent 看到的上下文，提升工具选择、输出格式和角色行为稳定性。这个章节本质不是“新 API”，而是“如何正确使用已有 API”。

### 主要手段

- System Prompt
- 工具描述
- 记忆注入
- 响应格式约束
- 拦截器动态改写请求

### 关键类

- `ModelInterceptor`
- `ModelRequest`
- `ModelResponse`
- `ModelCallHandler`
- `SystemMessage`
- `ReactAgent.outputType(...)`
- `ReactAgent.outputSchema(...)`

### 典型做法

#### 静态角色设定

直接在 `systemPrompt` / `instruction` 里写角色、边界、输出习惯。

#### 动态 Prompt

按用户角色、上下文、业务阶段动态拼 system prompt。

```java
public class DynamicPromptInterceptor extends ModelInterceptor {
    @Override
    public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
        String dynamicPrompt = "...";
        SystemMessage enhanced = request.getSystemMessage() == null
            ? new SystemMessage(dynamicPrompt)
            : new SystemMessage(request.getSystemMessage().getText() + "\n" + dynamicPrompt);

        ModelRequest modified = ModelRequest.builder(request)
            .systemMessage(enhanced)
            .build();
        return handler.call(modified);
    }
}
```

#### 响应格式控制

- `outputType(MyPojo.class)`
- `outputSchema(schemaString)`

### Claude 写代码时的建议

- 需要稳定结构化输出时，不要只靠提示词，优先 `outputType/outputSchema`
- 动态上下文注入优先拦截器或 Hook，不要散落在 service 层
- 工具说明文字也属于 context engineering，很关键

---

## 2.8 Agent Tool（智能体作为工具）

### 核心用途

把一个 `ReactAgent` 暴露成另一个 Agent 的工具，用来做多 Agent 编排。

### 适用场景

- 工具太多，一个 Agent 不好选
- 子任务专业化明显
- 上下文太大，想拆小
- 希望 supervisor 编排多个 specialist

### 核心类

- `ReactAgent`
- `AgentTool`
- `AgentTool.getFunctionToolCallback(...)`

### 最小实现

```java
ReactAgent writerAgent = ReactAgent.builder()
    .name("writer_agent")
    .model(chatModel)
    .description("负责写作")
    .instruction("你是专业作家")
    .build();

ReactAgent coordinator = ReactAgent.builder()
    .name("coordinator")
    .model(chatModel)
    .instruction("调用写作工具完成用户请求")
    .tools(AgentTool.getFunctionToolCallback(writerAgent))
    .build();
```

### 如何控制主 Agent 传给子 Agent 的输入

#### `inputSchema`

手写 JSON Schema，适合你想完全控制参数结构。

#### `inputType`

传 Java 类，让框架自动生成 schema。一般比 `inputSchema` 更适合业务代码。

### 如何控制子 Agent 输出

#### `outputSchema`

手写输出 schema。

#### `outputType`

传 Java 类型，让框架自动约束结构化输出。

### 最推荐模式

主 Agent 编排，子 Agent 类型化：

- 输入用 `inputType`
- 输出用 `outputType`

这样 schema 稳定、Java 类型安全最好。

### Claude 写代码时的建议

- 多 Agent 不是先上很多 Agent，而是先拆清边界
- 子 Agent 的 `name`、`description`、`instruction` 要非常清晰
- 强依赖结构化协作时，优先 `inputType + outputType`

---

## 2.9 Multi-agent

### 核心用途

构建 supervisor + specialists 的协作模式。

### 文档里的核心模式

- Tool Calling
  - 主 Agent 把子 Agent 当工具调
  - 控制流集中
- Handoffs
  - 当前 Agent 把控制权移交给另一个 Agent
  - 更适合多角色连续对话

### Claude 写代码时的建议

- 任务编排、审核链路优先 Tool Calling
- 需要“客服转专家”这类会话切换，才考虑 Handoffs

---

## 2.10 A2A（分布式智能体）

### 核心用途

让本地 Agent 发现并调用远程 Agent。

### 核心类

- `A2aRemoteAgent`
- `AgentCardProvider`

### 能干什么

- 从注册中心发现远程 AgentCard
- 基于 AgentCard 远程调用别的 Agent
- 和 Nacos 集成做注册与发现

### 最小实现

```java
A2aRemoteAgent remote = A2aRemoteAgent.builder()
    .name("data_analysis_agent")
    .agentCardProvider(agentCardProvider)
    .description("数据分析远程代理")
    .build();

Optional<OverAllState> result = remote.invoke("请根据季度数据给出分析概要");
```

### 配置重点

- `registry.enabled`
  - 当前服务要注册出去时启用
- `discovery.enabled`
  - 当前服务要发现别人时启用
- `server.card.name`
  - 必须与本地 `ReactAgent` Bean 名称一致

### Claude 写代码时的建议

- 远程 Agent 的名字、描述和注册信息要稳定，别在代码里随手改
- 本地先验证直连 Agent 可用，再接 A2A

---

## 2.11 Hooks / Interceptors / 控制与流式输出

### Hooks 能干什么

在 Agent 生命周期关键点插逻辑。

### 常见 Hook 类型

- `AgentHook`
  - Agent 开始/结束执行时触发
- `ModelHook`
  - 模型调用前后触发
- 还有消息相关 Hook 能做消息级更新

### 常见用途

- 注入系统上下文
- 读写记忆
- 审计日志
- 设停止条件
- 限制模型调用次数

### 关键类

- `HookPosition`
- `AgentHook`
- `ModelHook`
- `ModelCallLimitHook`
- `JumpTo`

### 最常用内置能力

#### 限制迭代次数

```java
ReactAgent agent = ReactAgent.builder()
    .name("agent")
    .model(chatModel)
    .hooks(ModelCallLimitHook.builder().runLimit(5).build())
    .build();
```

#### 自定义停止条件

在 `beforeModel` 检查状态、错误次数、是否已有答案，再决定结束。

### Interceptors 能干什么

拦截模型调用或工具调用。

### 常见拦截器

- `ModelInterceptor`
  - 动态 prompt、敏感词过滤、日志
- `ToolInterceptor`
  - 权限检查、缓存、重试、监控

### 流式输出

Agent 可返回 `Flux<NodeOutput>`，其中会区分：

- 模型流式片段
- 模型完成
- 工具完成
- Hook 完成

相关类型：

- `NodeOutput`
- `StreamingOutput`
- `OutputType`

### Claude 写代码时的建议

- 业务规则注入优先 Hook / Interceptor，不要塞进每个工具
- 成本风险高的 Agent 必加 `ModelCallLimitHook`
- 做前端流式展示时，不要只打印 token，要区分 tool/hook/model 事件

---

## 3. Graph Core

## 3.1 Quick Start

### 核心用途

用图结构表达工作流。每个节点产出状态，边决定流向。

### 核心类

- `StateGraph`
- `CompiledGraph`
- `OverAllState`
- `RunnableConfig`
- `START`
- `END`

### 最小心智模型

- `StateGraph`
  - 定义节点和边
- `compile()`
  - 编译成可运行图
- `invoke()/stream()`
  - 执行图
- 状态是所有节点共享的中心数据结构

### Claude 写代码时的建议

- Graph 不是 BPMN；它更像“可持久化的状态机 + 节点函数”
- 设计前先定义状态键，再写节点

---

## 3.2 Core Library（核心概念）

### 核心用途

定义 Graph 的基础构件：状态、节点、边、状态合并策略、序列化。

### 核心类

- `StateGraph`
- `OverAllState`
- `KeyStrategyFactory`
- `KeyStrategy`
- `AppendStrategy`
- `ReplaceStrategy`
- `RemoveByHash`

### 最关键概念：状态键策略

多个节点可能都返回 `Map<String, Object>`，同一个 key 怎么合并，靠 `KeyStrategy`。

#### `ReplaceStrategy`

新值覆盖旧值。

适合：

- 当前步骤结果
- 单值标志位
- 当前阶段状态

#### `AppendStrategy`

新值追加到旧值之后。

适合：

- `messages`
- 日志列表
- 结果列表

#### 删除追加列表中的元素

文档提供 `RemoveByHash.of(value)`。

### 最小实现

```java
KeyStrategyFactory keyStrategyFactory = () -> {
    Map<String, KeyStrategy> m = new HashMap<>();
    m.put("messages", new AppendStrategy());
    m.put("value", new ReplaceStrategy());
    return m;
};

StateGraph graph = new StateGraph(keyStrategyFactory);
```

### Claude 写代码时的建议

- `messages` 基本默认 `AppendStrategy`
- 计数器、当前状态、标志位通常 `ReplaceStrategy`
- 如果策略没想清楚，Graph 行为会很怪，先定义状态 schema 再编码

---

## 3.3 Graph 序列化与自定义状态

### 核心用途

让 Graph 状态能被可靠持久化和恢复，尤其是自定义对象。

### 关键点

#### 1. 自定义状态对象要可序列化

文档示例推荐基于 Jackson 注解：

- `@JsonCreator`
- `@JsonProperty`
- `@JsonIgnoreProperties`

#### 2. 可以定制默认 Serializer

从 `StateGraph.getStateSerializer()` 拿到序列化器，再定制底层 `ObjectMapper`。

#### 3. 可以直接给 Graph 传自定义序列化器

推荐复杂场景直接给 `StateGraph` 指定自定义 serializer。

### 相关类

- `StateSerializer`
- `SpringAIJacksonStateSerializer`
- `ObjectMapper`

### Claude 写代码时的建议

- 状态里不要随便塞不可序列化对象
- 自定义消息类、自定义领域对象进状态前，先解决 Jackson 序列化问题

---

## 3.4 Graph Memory（图级记忆/持久化）

### 核心用途

让图的状态在多次执行之间保留，也就是“图的记忆”。

### 核心类

- `MemorySaver`
- `RedisSaver`
- `SaverConfig`
- `CompileConfig`

### 最小实现

```java
SaverConfig saverConfig = SaverConfig.builder()
    .register(new MemorySaver())
    .build();

CompiledGraph graph = stateGraph.compile(
    CompileConfig.builder()
        .saverConfig(saverConfig)
        .build()
);
```

### 怎么干

- 编译时把 saver 配进去
- 运行时通过 `RunnableConfig.threadId` 指定会话
- 同一 `threadId` 会恢复同一份状态

### 生产建议

- demo 用 `MemorySaver`
- 生产用数据库/Redis 支持的 saver

---

## 3.5 Persistence 示例

### 核心用途

展示“任意 StateGraph 都能加持久化”。

### 关键步骤

1. 创建 `Checkpointer`/Saver
2. 编译图时传 `CompileConfig`
3. 执行时传 `threadId`

### Claude 写代码时的建议

- 用户会跨轮回来继续执行的 Graph，一开始就设计成可持久化
- `threadId` 要来自业务会话 ID，不要随机生成后又找不回

---

## 3.6 Long-running Task（持久化执行）

### 核心用途

把长任务拆成可恢复、可重试、可分批执行的图。

### 文档强调的点

- 编译时配置持久化
- 执行时带 `threadId`
- 有副作用的操作要慎重设计

### 推荐做法

把大任务拆成多个幂等节点，不要把“多步副作用”塞进一个节点。

例如：

- `process_data`
- `check_complete`
- 未完成则回到 `process_data`

### 典型状态键

- `items`
- `processedCount`
- `results`

### Claude 写代码时的建议

- 节点内尽量单一职责
- 长任务要记录进度，例如 `processedCount`
- 副作用节点要能重放或幂等

---

## 3.7 Wait User Input（等待用户输入）

### 核心用途

在 Graph 中做人工审批、人工确认、交互式中断恢复。

### 核心能力

- 在某节点前中断
- 外部拿到当前状态
- 外部更新状态
- 继续执行

### 核心类

- `CompileConfig.interruptBefore(...)`
- `CompiledGraph.getState(...)`
- `CompiledGraph.updateState(...)`

### 典型流程

1. 图跑到 `human_feedback` 前中断
2. 外部 UI 或人工输入 `back/next`
3. 把输入写回状态
4. 图从中断点继续跑

### Claude 写代码时的建议

- 任何“审批/确认/人工修正”场景，优先考虑这个模式
- 人工输入值最好做枚举化，别依赖自由文本分支

---

## 3.8 Subgraph as NodeAction（子图作为节点）

### 核心用途

把一个完整子流程封装成父图里的一个节点，实现模块化复用。

### 核心类

- `CompiledGraph`
- `NodeAction`

### 怎么干

1. 先定义并编译子图
2. 写一个 `NodeAction` 包装器
3. 在父图节点中调用子图
4. 做父子状态映射

### 最小模式

```java
public class SubGraphNode implements NodeAction {
    private final CompiledGraph subGraph;

    public Map<String, Object> apply(OverAllState parentState) {
        Map<String, Object> subInput = Map.of("input", parentState.value("data").orElse(""));
        OverAllState subResult = subGraph.invoke(subInput);
        return Map.of("processed", subResult.value("result").orElse(""));
    }
}
```

### 适合的场景

- 公共处理子流程
- 多版本流程封装
- 复杂逻辑分层

### Claude 写代码时的建议

- 父子图之间一定要先设计输入映射和输出映射
- 子图不要偷偷依赖父图内部状态结构

---

## 3.9 LLM Streaming

### 核心用途

在 Graph 节点中接入 Spring AI 的流式输出能力。

### 核心类

- `ChatClient`
- `Flux<ChatResponse>`
- `Flux<String>`

### 怎么干

- 直接用 `chatClient.prompt().stream().chatResponse()`
- 或节点里返回流式内容

### Claude 写代码时的建议

- 如果上层消费方是 WebFlux/SSE，就别把流先收集成完整字符串
- Graph 里流式节点要明确消费者如何取走流

---

## 3.10 Time Travel（状态历史回溯）

### 核心用途

查看和恢复 Graph 的历史状态，方便调试、回放、人工修正。

### 依赖前提

必须先配置 checkpoint/saver。

### Claude 写代码时的建议

- 对高价值流程，保留回溯能力非常有用
- 它适合调试、审计、回滚，不适合拿来替代正常业务状态设计

---

## 3.11 Adaptive RAG

### 核心用途

展示如何用 Graph 把 RAG 做成“检索-评估-调整-再检索”的自适应流程，而不是一次性查完就答。

### 文档体现的关键思路

- 用 `ChatClient` 做打分/评估器
- 把评估逻辑做成函数/节点
- 根据评分结果决定是否继续检索、重写问题、重试生成

### Claude 写代码时的建议

- 如果用户需求是“提高 RAG 质量”，先考虑把评估节点图化，而不是只加提示词

---

## 4. 最常用组合套路

## 4.1 一个带记忆和工具的 Agent

优先组合：

- `ReactAgent`
- `ChatModel`
- `FunctionToolCallback`
- `MemorySaver`
- `RunnableConfig.threadId`

适合：

- 聊天助手
- 业务问答助手
- 会调用外部系统的 Copilot

## 4.2 一个可恢复工作流

优先组合：

- `StateGraph`
- `AppendStrategy` / `ReplaceStrategy`
- `CompileConfig + SaverConfig`
- `RunnableConfig.threadId`

适合：

- 长流程审批
- 数据处理管道
- 人机协同任务

## 4.3 多 Agent 编排

优先组合：

- 主 `ReactAgent`
- 子 `ReactAgent`
- `AgentTool.getFunctionToolCallback(...)`
- 子 Agent 的 `inputType/outputType`

适合：

- 规划 -> 执行 -> 审核
- 写作 -> 评审
- 检索 -> 分析 -> 总结

## 4.4 短期记忆 + 长期画像

优先组合：

- `MemorySaver`
- `MemoryStore`
- `RunnableConfig.metadata("user_id", ...)`
- `ModelHook.beforeModel`

适合：

- 记住用户偏好
- 跨会话个性化
- 客服/助理类 Agent

---

## 5. Claude 写代码时的硬性约束

### 5.1 先定义状态键

在 Graph 里，先写清楚这些键：

- `messages`
- `input`
- `result`
- `processedCount`
- `human_feedback`
- `user_profile`

每个键明确：

- 类型
- merge strategy
- 谁负责写
- 谁负责读

### 5.2 先选记忆层

- 同会话上下文：`MemorySaver`
- 跨会话资料：`Store/MemoryStore`

不要混着乱存。

### 5.3 能类型化就类型化

优先：

- `inputType(MyRequest.class)`
- `outputType(MyResponse.class)`

少用大段自由 JSON schema 字符串，除非确实要跨语言精确控制。

### 5.4 把动态上下文放 Hook/Interceptor

不要在 Controller、Service、Tool 里到处手工拼 prompt。

### 5.5 工具描述必须精确

工具选得准不准，很多时候取决于：

- 工具名
- 描述
- 输入参数结构

这三项写差了，模型就会乱调。

---

## 6. 一个推荐骨架

如果 Claude 要从零写一个“有工具、有记忆、可扩展”的 Spring AI Alibaba 应用，可以按这个顺序：

1. 定义 `ChatModel`
2. 定义请求/响应 POJO
3. 定义工具 `FunctionToolCallback`
4. 定义 `MemorySaver`
5. 如需长期记忆，定义 `MemoryStore`
6. 定义 `ModelHook` 注入用户画像
7. 构建 `ReactAgent`
8. 所有调用统一带 `RunnableConfig.threadId`
9. 如果任务开始变复杂，再下沉到 `StateGraph`

---

## 7. 给 Claude 的一句话结论

Spring AI Alibaba 的核心不是“再包一层 LLM”，而是把 Java AI 应用拆成两条主线：

- `ReactAgent` 负责智能体推理、工具调用、记忆与控制
- `StateGraph` 负责工作流状态、可恢复执行、中断、持久化与复杂编排

实际开发里，优先把需求判断为“Agent 问题”还是“Graph 问题”，选对主轴，代码会清晰很多。

---

## 8. 官方文档来源页

本说明主要基于这些官方文档页整理：

- https://java2ai.com/docs/frameworks/agent-framework/tutorials/agents
- https://java2ai.com/docs/frameworks/agent-framework/tutorials/messages
- https://java2ai.com/docs/frameworks/agent-framework/tutorials/memory
- https://java2ai.com/en/docs/frameworks/agent-framework/tutorials/tools
- https://java2ai.com/en/docs/frameworks/agent-framework/tutorials/models
- https://java2ai.com/docs/frameworks/agent-framework/advanced/memory
- https://java2ai.com/docs/frameworks/agent-framework/advanced/context-engineering
- https://java2ai.com/docs/frameworks/agent-framework/advanced/agent-tool
- https://java2ai.com/en/docs/frameworks/agent-framework/advanced/multi-agent
- https://java2ai.com/docs/frameworks/agent-framework/advanced/a2a
- https://java2ai.com/docs/frameworks/graph-core/core/core-library
- https://java2ai.com/en/docs/frameworks/graph-core/core/memory
- https://java2ai.com/docs/frameworks/graph-core/examples/persistence
- https://java2ai.com/docs/frameworks/graph-core/examples/long-time-running-task
- https://java2ai.com/docs/frameworks/graph-core/examples/wait-user-input
- https://java2ai.com/en/docs/frameworks/graph-core/examples/subgraph-as-nodeaction
- https://java2ai.com/docs/frameworks/graph-core/examples/llm-streaming-springai
- https://java2ai.com/docs/frameworks/graph-core/examples/time-travel
- https://java2ai.com/docs/frameworks/graph-core/examples/adaptiverag
