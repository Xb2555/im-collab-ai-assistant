# Repository Guidelines

## 项目目标与验收边界
本项目面向“Agent-Pilot · 从 IM 对话到演示稿的一键智能闭环”赛题。所有实现必须围绕一个核心原则展开：`AI Agent 是主驾驶，GUI 是仪表盘和辅助操作台`。功能设计必须覆盖 IM、文档，以及 PPT 或自由画布至少一种，并且至少支持一次多场景组合编排，不允许只做单点生成器或固定链路 Demo。

必须长期对齐以下能力目标：
- 支持通过自然语言入口启动任务，文本优先，语音可后补。
- 具备任务理解、规划、执行、反馈闭环，而不是仅调用单轮 LLM。
- 支持移动端与桌面端之间的状态、内容、进度双向同步。
- 输出可演示、可归档、可分享的最终成果。

## 场景拆分约束
实现与文档请尽量映射到以下场景模块，保持可独立演示、可组合编排：
- `A`：IM 意图/指令入口。
- `B`：任务理解与规划（Planner / Agent orchestration）。
- `C`：文档或白板生成与编辑。
- `D`：演示稿或自由画布生成、排练、修改。
- `E`：多端协作、一致性、冲突处理。
- `F`：总结、交付、归档。

新增模块、接口、服务时，请在命名、注释或说明中明确其属于哪个场景，避免职责混杂。

## 仓库结构与模块方向
本仓库是 Java 17 的 Maven 多模块 Spring Boot 项目。根 `pom.xml` 当前聚合：
- `im-collab-ai-assistant-common`：放通用模型、协议、公共工具、跨模块基础能力。
- `im-collab-ai-assistant-planner`：放任务拆解、步骤编排、Agent 流程控制相关逻辑。
- `src/main`：当前承载根应用入口与基础配置。

后续如果增加 IM、Doc、Presentation、Sync 等能力模块，优先保持模块边界清晰，不要把业务编排、第三方接入、同步逻辑都堆进同一个模块。

## 开发与运行命令
在仓库根目录执行：
- `./mvnw clean test`：运行全部测试。
- `./mvnw clean package`：构建所有模块。
- `./mvnw spring-boot:run`：启动根应用。
- `./mvnw -pl im-collab-ai-assistant-planner test`：只验证 `planner` 模块。

## 编码与设计约定
统一使用 4 空格缩进、标准 Java 风格，包名保持在 `com.lark.imcollab...` 下。类名使用 `PascalCase`，方法/字段使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`。

实现时优先遵守以下设计原则：
- 先抽象场景接口，再补具体渠道或第三方平台适配。
- 规划、执行、同步、交付分层，不写“大而全”服务类。
- 优先保留可组合能力，不要把主流程写死成单一顺序。
- GUI 相关代码只负责展示状态、确认节点和细粒度调整，不替代 Agent 决策。
- PPT 首次生成链路中，图片素材优先下载到仓库工作目录内，再在 Slides XML 中使用 `@./relative/path` 让 `slides +create --slides` 自动上传；不要先把系统临时目录文件传给 `+media-upload`。
- PPT 迭代链路中，已有演示文稿的图片替换或增量插图，统一走 `+media-upload` 获取 `file_token`，再写入 `<img src="boxcn...">`。
- 图片搜索节点只允许返回可直接下载的原始图片 URL，不允许返回素材详情页、搜索页、跳转页或需要二次解析的页面 URL。

## 测试与验证要求
测试基于 `spring-boot-starter-test` 和 JUnit 5。测试类使用 `*Tests` 后缀，放在对应模块 `src/test/java` 下。新增能力至少覆盖以下一种验证：
- Planner 是否正确拆解任务与步骤。
- 场景模块之间的输入输出契约是否稳定。
- 多端同步或状态流转是否正确。
- 文档、演示稿、归档产物的生成链路是否可回归。

提交前至少运行一次 `./mvnw clean test`。

## 提交与 PR 要求
提交信息沿用 Conventional Commits，如 `feat:`、`fix:`、`refactor:`。每次提交聚焦一个逻辑变更。PR 说明中应明确：
- 对应赛题场景（A-F）。
- 是否影响 IM、Doc、PPT/Canvas 或多端同步。
- 测试方式与结果。
- 演示路径或示例输入输出。

## Agent 协作说明
后续任何 agent 在实现功能前，都应先判断该需求属于哪个场景模块、是否需要跨场景编排、是否影响多端一致性。不要只为了快速出效果而绕过 Planner、跳过状态建模、或把核心流程硬编码到界面层。`application.yaml` 中不得提交密钥、令牌或生产环境配置。
