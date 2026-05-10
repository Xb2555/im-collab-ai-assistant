# PPT 生成迭代架构整改方案

## 1. 文档定位

本文是给 Claude 直接用于改代码的实施型架构文档，目标是：**让 PPT 生成支持图文并茂的精美内容**。

目标文件路径：
- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/`
- `im-collab-ai-assistant-harness/src/main/java/com/lark/imcollab/harness/presentation/config/PresentationAgentConfig.java`

---

## 2. 当前真实状态判断

### 2.1 当前已经到位的部分

当前代码已经具备以下基础：

1. **Storyline Agent** - 生成叙事主线（title、audience、goal、narrativeArc、style）
2. **Outline Agent** - 生成页面大纲（slides、templateVariant、visualEmphasis）
3. **SlideXml Agent** - 生成飞书 Slides XML
4. **Review Agent** - 审查 PPT 初稿

关键文件：
- `PresentationWorkflowNodes.java` - 工作流编排
- `PresentationAgentConfig.java` - Agent 配置

### 2.2 当前真正的断点

| 断点 | 说明 |
|------|------|
| **不支持图片** | `presentationSlideXmlAgent` 第93行明确"不使用图片" |
| **不支持图表** | 首版限制为 shape/text/rect/line |
| **不支持插画** | 无插画资源接入 |
| **不支持搜索** | 无图片搜索规划节点 |

---

## 3. 当前问题的代码级根因

### 3.1 PresentationAgentConfig 限制了图片能力

```java
// PresentationAgentConfig.java:93
3. 首版只使用 shape(type=text/rect)、line、content、p、ul、li，不使用图片、表格、chart、动画
```

这是有意限制，但现在成为障碍。

### 3.2 SlideXml 生成只产 XML，无图片资源规划

当前工作流：

1. `generateSlideXml()` → 生成 XML（含 rect 模拟卡片）
2. 缺少独立的**图片资源规划节点**
3. 缺少**插画资源规划节点**
4. 缺少**图表（Mermaid）生成节点**

### 3.3 无图片搜索规划 Agent

对比网页生成的图片规划节点：

```java
// 网页生成有
- contentImageTasks: 搜索内容图片
- illustrationTasks: 获取插画
- diagramTasks: Mermaid 图表
- logoTasks: AI 生成 Logo
```

PPT 生成**完全没有**对应的规划节点。

---

## 4. 整改方案：新增图片资源规划节点

### 4.1 新增 PresentationImagePlanner Agent

**目标文件**：创建 `PresentationImagePlannerAgent.java` 或在 `PresentationAgentConfig.java` 新增 Bean

```java
@Bean(name = "presentationImagePlannerAgent")
public ReactAgent presentationImagePlannerAgent(ChatModel chatModel) {
    return ReactAgent.builder()
        .name("presentation-image-planner-agent")
        .description("Scenario D: 为 PPT 页面规划图片资源")
        .systemPrompt("""
            你是一个专业的 PPT 图片收集规划师。你的任务是根据 PPT 页面计划，为每页规划合适的图片、插画和图表资源。

            ## 图片类型说明

            ### 1. 内容图片 (contentImageTasks)
            - 用途：PPT 的内容配图，说明内容、佐证观点
            - 来源：通过关键词搜索获取
            - 示例：产品界面截图、团队照片、场景图片、数据图表照片等
            - 使用场景：cover、section、two-column 等页面

            ### 2. 插画图片 (illustrationTasks)
            - 用途：装饰性插画，提升页面美观度和视觉层次
            - 来源：Undraw 插画库（https://undraw.co/）
            - 示例：抽象概念插画、扁平化图标、点缀图形等
            - 使用场景：背景装饰、要点点缀、过渡页

            ### 3. 图表 (diagramTasks)
            - 用途：展示架构、流程、对比、数据关系
            - 来���：Mermaid 代码生成
            - 示例：系统架构图、流程图、组织结构图、饼图、柱状图等
            - 使用场景：metric-cards、comparison、timeline 等需要数据可视化的页面

            ## 输入上下文

            - PPT 标题：%s
            - 风格：%s（deep-tech、business-light、fresh-training、vibrant-creative、minimal-professional）
            - 页面计划列表：%s（每页的 slideId、title、keyPoints、layout、templateVariant、visualEmphasis）
            - 上游文档摘要（如果有）：%s

            ## 规划原则

            1. 需求导向：根据页面计划中的 visualEmphasis、layout、keyPoints 来规划
               - visualEmphasis=title：优先标题装饰图
               - visualEmphasis=balance：需要内容图配文字
               - visualEmphasis=data：需要图表
               - visualEmphasis=action：需要示意性插画
            2. 适量原则：每页 1-2 张图片，避免过多影响阅读
            3. 关键词精准：选择最能体现页面内容的搜索词
            4. 描述清晰：为每个任务提供清晰的用途说明和位置建议
            5. 风格一致：插画风格要符合 PPT 全局风格

            ## 输出要求

            请严格按照以下 JSON 格式返回图片收集计划：

            ```json
            {
              "pagePlans": [
                {
                  "slideId": "slide-1",
                  "contentImageTasks": [
                    {
                      "query": "搜索关键词",
                      "purpose": "图片用途说明，如'用于展示产品界面，位于页面右侧'"
                    }
                  ],
                  "illustrationTasks": [
                    {
                      "query": "插画关键词",
                      "purpose": "插画用途说明，如'作为背景点缀，位于页面左下角'"
                    }
                  ],
                  "diagramTasks": [
                    {
                      "mermaidCode": "mermaid图表代码",
                      "purpose": "图表用途说明"
                    }
                  ]
                }
              ]
            }
            ```

            注意：
            - 如果某一页不需要图片，对应数组可以为空
            - mermaidCode 要是有效的 Mermaid 语法（仅支持：flowchart、graph、sequenceDiagram、stateDiagram-v2、erDiagram）
            - 关键词使用中文或英文均可，选择搜索效果最好的语言
            - 每个 task 都要有 purpose 说明具体用途和放置位置
            """)
        .outputType(PresentationImagePlan.class)
        .model(chatModel)
        .build();
}
```

### 4.2 新增数据模型

**目标文件**：创建 `model/PresentationImagePlan.java`

```java
package com.lark.imcollab.harness.presentation.model;

import java.util.List;

public class PresentationImagePlan {
    private List<PageImagePlan> pagePlans;

    public static class PageImagePlan {
        private String slideId;
        private List<ImageTask> contentImageTasks;
        private List<ImageTask> illustrationTasks;
        private List<DiagramTask> diagramTasks;

        public static class ImageTask {
            private String query;
            private String purpose;
        }

        public static class DiagramTask {
            private String mermaidCode;
            private String purpose;
        }
    }
}
```

### 4.3 新增资源获取 Agent

**目标文件**：在 `PresentationAgentConfig.java` 新增 Bean

```java
@Bean(name = "presentationImageFetcherAgent")
public ReactAgent presentationImageFetcherAgent(ChatModel chatModel) {
    return ReactAgent.builder()
        .name("presentation-image-fetcher-agent")
        .description("Scenario D: 获取 PPT 所需的图片资源")
        .systemPrompt("""
            你负责根据图片规划获取实际的图片资源。

            ## 你的任务

            1. **内容图片**：使用搜索工具获取与 query 相关的图片 URL
            2. **插画图片**：从 Undraw (https://undraw.co/) 获取匹配风格的插画
            3. **图表**：将 Mermaid 代码转换为飞书画板 DSL

            ## 输入

            - 图片规划 JSON：包含 contentImageTasks、illustrationTasks、diagramTasks

            ## 输出

            ```json
            {
              "resources": [
                {
                  "slideId": "slide-1",
                  "contentImages": [{"url": "图片URL", "purpose": "用途"}],
                  "illustrations": [{"url": "图片URL", "purpose": "用途"}],
                  "diagrams": [{"whiteboardDsl": "飞书画板DSL", "purpose": "用途"}]
                }
              ]
            }
            ```

            规则：
            1. 优先使用可靠的图片源（如 Unsplash、Pexels、Undraw）
            2. 图片 URL 必须可直接访问
            3. Mermaid 转飞书画板使用标准 DSL 格式
            """)
        .outputType(PresentationImageResources.class)
        .model(chatModel)
        .build();
}
```

---

## 5. 工作流整改

### 5.1 整改后的工作流

```
原工作流：
storyline → outline → slideXml → review

整改后工作流：
storyline → outline → imagePlan → imageFetch → slideXml → review
```

### 5.2 新增节点

| 节点 | 职责 | 输入 | 输出 |
|------|------|------|------|
| **imagePlan** | 为每页规划图片资源 | outline | PresentationImagePlan |
| **imageFetch** | 获取实际的图片 URL | imagePlan | PresentationImageResources |
| **slideXmlGen** | 生成含图片引用的 XML | outline + resources | slideXmlList |

### 5.3 整改 PresentationWorkflowNodes

**目标文件**：`PresentationWorkflowNodes.java`

新增三个方法：

```java
public CompletableFuture<Map<String, Object>> planImages(OverAllState state, RunnableConfig config) {
    // 调用 presentationImagePlannerAgent
    // 生成 PresentationImagePlan
}

public CompletableFuture<Map<String, Object>> fetchImages(OverAllState state, RunnableConfig config) {
    // 调用 presentationImageFetcherAgent
    // 获取实际图片 URL
}

public CompletableFuture<Map<String, Object>> generateSlideXmlWithImages(OverAllState state, RunnableConfig config) {
    // 生成含图片引用的 XML
    // 使用 XML <img src="..." /> 元素
}
```

---

## 6. SlideXml 生成整改

### 6.1 支持 img 元素

当前 `presentationSlideXmlAgent` 限制：

```java
3. 首版只使用 shape(type=text/rect)、line、content、p、ul、li，不使用图片
```

需要改为：

```java
3. 支持 shape(type=text/rect/line)、content、p、ul/li、img
4. img 元素：<img src="图片URL" topLeftX="..." topLeftY="..." width="..." height="..."/>
5. 图片位置根据页面布局（layout）和视觉重点（visualEmphasis）计算
```

### 6.2 位置计算规则

| layout | 图片位置 | 建议宽高 |
|--------|---------|----------|
| cover | 右侧 1/3 区域 | width=320, height=180 |
| section | 左侧或右侧 40% | width=360, height=200 |
| two-column | 各自底部或右侧 | width=280, height=160 |
| metric-cards | 每个卡片内 | width=120, height=80 |
| comparison | 左侧/右侧对齐 | width=300, height=180 |

### 6.3 更新 SlideXml Agent Prompt

```java
// 在 PresentationAgentConfig.java 中更新 presentationSlideXmlAgent
.systemPrompt("""
    你负责为整份 PPT 生成飞书 Slides XML，支持图片插入。
    
    ## 新增能力
    
    - 支持 <img src="URL" /> 元素
    - 图片来自 imageFetch 阶段的资源
    - 支持图表用 Mermaid 生成后转为飞书画板
    
    ## XML 规则（更新版）
    
    1. 画布为 960x540，所有坐标必须在画布内
    2. slide 直接子元素：style、data、note
    3. data 下可用：shape(type=text/rect/ellipse/line)、content、p、ul/li、img、chart
    4. img 元素：<img src="URL" topLeftX="..." topLeftY="..." width="..." height="..." alpha="0.9"/>
    5. 支持渐变背景：<fill><fillColor color="linear-gradient(...)"/></fill>
    6. 支持图标：<icon iconType="iconpark/..." .../>
    
    ## 坐标计算规则
    
    - cover：标题在左，图片在右 1/3 区域
    - section：标题+要点在左，图片在右 40%
    - two-column：左栏内容+右栏图片 或 反之
    - metric-cards：每个卡片内嵌小图
    
    ## 风格映射
    
    | style | 主色 | 背景 | 文字色 |
    |-------|------|------|--------|
    | deep-tech | rgb(59,130,246) | 深蓝渐变 | 白色 |
    | business-light | rgb(30,60,114) | 浅灰 | 深灰 |
    | fresh-training | rgb(34,197,94) | 白色 | 深灰 |
    | vibrant-creative | 紫粉渐变 | 紫粉渐变 | 白色 |
    | minimal-professional | rgb(59,130,246) | 浅灰+顶部色条 | 深色 |
    
    ## 完整示例（带图片）
    
    <slide xmlns="http://www.larkoffice.com/sml/2.0">
      <style><fill><fillColor color="linear-gradient(135deg,rgb(15,23,42,1) 0%,rgb(56,97,140,1) 100%)"/></fill></style>
      <data>
        <shape type="text" topLeftX="48" topLeftY="40" width="480" height="60">
          <content textType="title"><p><span color="white" fontSize="36">产品介绍</span></p></content>
        </shape>
        <shape type="text" topLeftX="48" topLeftY="120" width="480" height="140">
          <content textType="body"><ul><li><p><span color="rgb(200,210,230)">核心功能一</span></p></li><li><p><span color="rgb(200,210,230)">核心功能二</span></p></li></ul></content>
        </shape>
        <img src="https://example.com/product.png" topLeftX="560" topLeftY="80" width="360" height="200" alpha="0.95"/>
      </data>
    </slide>
    """)
```

---

## 7. 验收标准

### 7.1 功能验收

| 功能 | 验收标准 |
|------|----------|
| imagePlan | 能为每页规划 0-2 张图片 |
| imageFetch | 能返回可访问的图片 URL |
| slideXml | 能生成含 `<img>` 的 XML |
| 位置计算 | 图片不超出画布边界 |
| 风格一致 | 配色符合全局风格 |

### 7.2 质量验收

| 指标 | 目标 |
|------|------|
| 每页图片数 | ≤ 2 张 |
| 图片搜索成功率 | ≥ 80% |
| XML 解析成功率 | 100% |
| 风格一致性 | 配色/字号统一 |

---

## 8. 实施步骤

### 步骤 1：新增数据模型

- 创建 `model/PresentationImagePlan.java`
- 创建 `model/PresentationImageResources.java`

### 步骤 2：新增 Agent

- 在 `PresentationAgentConfig.java` 新增 `presentationImagePlannerAgent`
- 在 `PresentationAgentConfig.java` 新增 `presentationImageFetcherAgent`

### 步骤 3：更新工作流

- 在 `PresentationWorkflowNodes.java` 新增 `planImages()`
- 在 `PresentationWorkflowNodes.java` 新增 `fetchImages()`
- 修改 `generateSlideXml()` 支持图片

### 步骤 4：更新 SlidesTool

- 确保 LarkSlidesTool 支持 `<img>` 元素
- 测试图片插入功能

### 步骤 5：联调测试

- 端到端测试完整工作流
- 验证图片加载和显示效果

---

*本文档基于代码分析得出，用于指导 PPT 生成能力迭代*