# PPT 生成 MVP 阶段总结

## 当前目标

当前阶段目标是先跑通一条最小可用的飞书原生 PPT 生成链路：

- 从任务输入生成结构化 PPT 页面大纲
- 为需要配图的页面搜索并下载真实图片
- 将图片上传到飞书 Slides
- 按页生成飞书可渲染 XML
- 产出一份带图文的飞书原生 PPT

当前阶段不追求成品级设计，只要求链路可跑通、页面有基础图文分离、后续可以继续迭代。

## 本阶段已经完成的改动

### 1. 图片来源链路改为代码侧真实搜索与下载

之前的问题是：

- LLM 直接返回图片 URL，不稳定
- 容易返回详情页、跳转页、不可下载 URL
- 下载失败后页面退化明显

当前已改为：

- 图片搜索优先走 Pexels
- 只接受可直接下载的原始图片 URL
- 下载到仓库根目录 `.ppt-generated-assets/`
- 下载失败时自动尝试候选 URL
- 再通过 `slides +media-upload` 上传到飞书，获得 `file_token`
- 在 XML 中使用 `boxcn...` 形式的图片引用

### 2. 加入图片调试日志

为了便于本地追查图片问题，当前在关键节点加入了 `System.err.println()` 调试日志，包含：

- 搜索 query
- 候选图片 URL 列表
- 实际选中的图片 URL
- 下载结果
- 上传结果
- slide IR 中最终绑定的图片信息

### 3. 接入 Pexels API 配置

已支持通过 `application.yml` 从环境变量读取：

- `pexels.api-key: ${PEXELS_API_KEY:}`

并已按当前仓库约定接到 `.env`。

### 4. Prompt 从代码中解耦

原先写死在 Java 里的 PPT 生成 prompt 已拆到：

- `im-collab-ai-assistant-harness/src/main/resources/prompt/ppt-style/`

当前至少包含：

- `storyline-system-prompt.txt`
- `outline-system-prompt.txt`
- `image-plan-system-prompt.txt`
- `slide-xml-system-prompt.txt`
- `review-system-prompt.txt`

这样后续调页数、调风格、调大纲规则，不需要继续改 Java 常量字符串。

### 5. 页面语义建模已初步补齐

当前 `PresentationSlidePlan` 和 `PresentationSlideIR` 已补充：

- `pageType`
- `pageSubType`
- `sectionId`
- `sectionTitle`
- `sectionOrder`

这为后续目录页、过渡页、正文页、感谢页、以及 PPT 迭代修改打下了基础。

### 6. fallback 大纲从“固定普通页”改为“结构化页组”

当前 fallback 已不是简单的：

- 封面
- 若干普通正文
- 总结

而是结构化展开为：

- `COVER`
- `TOC`
- `TRANSITION`
- 若干正文页
- `THANKS`

正文页会按顺序轮换不同版式：

- `two-column`
- `timeline`
- `comparison`
- `metric-cards`

### 7. 新增专属页面模板

当前已补上独立模板：

- 目录页 `TOC`
- 过渡页 `TRANSITION`
- 感谢页 `THANKS`
- 封面页 `COVER`
- 双栏正文 `CONTENT.HALF_IMAGE_HALF_TEXT`

不再全部挤在单一 `section` 模板里。

### 8. 图文分离做了第一轮修正

当前已做的修正包括：

- 封面图作为全屏背景图，不再把标题文字直接压在图上
- 封面前景加入白色标题卡，保证中文标题可读
- 普通双栏页保留独立图片区，图片不再直接盖住正文
- 过渡页已支持复用封面图作为背景
- 普通正文页左右两个卡片已放大，避免页面中心只剩两个很小的框

### 9. 页数上限从多处统一放宽到 12 页

已统一调整：

- Java 侧 `MAX_SLIDES = 12`
- storyline prompt 从“最多 10 页”改为“最多 12 页”
- outline prompt 从“1 <= 页数 <= 10”改为“1 <= 页数 <= 12”

同时保留当前 MVP 阶段的 fallback 默认值：

- 若用户未明确指定页数，优先尝试生成约 8 页的结构化稿

## 当前整体生成链路

### A. 任务进入与参数解析

入口节点会从任务、上下文、PPT 步骤摘要中提取：

- 标题
- 风格
- 受众
- 页数
- 密度
- 是否需要 speaker notes

输出 `PresentationGenerationOptions`。

### B. storyline 生成

`presentationStorylineAgent` 负责生成：

- PPT 标题
- 汇报目标
- 叙事结构
- 核心信息
- 建议页数

如果 agent 输出不完整，会进入 normalize 和 fallback 兜底。

### C. outline 生成

`presentationOutlineAgent` 负责输出页面数组，每页带：

- 标题
- 要点
- layout
- templateVariant
- pageType / pageSubType
- section 信息

如果大纲为空，或结构过于薄弱，会回退到结构化 fallback 页组。

### D. 视觉/素材规划

图像规划阶段会为每页生成：

- 内容图任务
- 插画任务
- 图表任务

然后代码侧执行真实图片搜索与候选下载。

### E. 资源下载与上传

当前资源处理流程为：

1. 搜图
2. 得到多个候选原图 URL
3. 下载到 `.ppt-generated-assets/`
4. 上传到飞书 Slides
5. 获得 `file_token`
6. 将图片信息绑定到页面资源对象

### F. 构建 Presentation IR

当前会把页面计划和资源组装成 `PresentationIR` / `PresentationSlideIR`，并为各 slide 生成：

- title element
- body element
- image element

这个 IR 是生成 XML 以及后续做迭代修改的重要中间层。

### G. 生成 Slide XML

当前 `compileSlideXml()` 会按页面类型/布局生成 XML：

- 封面页：背景图 + 标题安全区
- 目录页：有序列表
- 过渡页：章节标题 + 摘要 + 背景图
- 普通正文页：文字区 + 图片区
- 感谢页：结束页

### H. 写入飞书 PPT

当前遵循仓库内已验证过的稳定链路：

1. `slides +create` 创建空白演示文稿
2. `slides +media-upload` 上传图片
3. `xml_presentation.slide.create` 逐页创建 XML 页面

不依赖 `slides +create --slides` 来承载真实生产页面内容。

## 当前还存在的缺陷

### 1. 视觉质量仍然只是 MVP 水平

虽然已经避免了“纯贴图压字”，但当前页面仍然偏模板化：

- 配色体系还比较基础
- 字号层级还不够精细
- 卡片、留白、装饰细节不够成熟
- 与 Kimi 那种成品感还有明显差距

### 2. 目录页语义仍然不够稳定

当前目录页是从已有 `keyPoints` 再做一轮压缩得到短句。

这意味着：

- 如果上游 keyPoints 本身写得很散，目录仍然可能不够像“章节标题”
- 目录的最佳做法应该是直接使用独立的 `sectionTitle`

这部分后续应在页面建模和大纲生成阶段继续收敛。

### 3. 过渡页图片还只是“复用封面图”

当前过渡页插图策略是：

- 没有独立图片时，复用封面图

这能快速改善观感，但仍有不足：

- 所有过渡页可能显得重复
- 还没有做到封面图镜像、裁切、弱化等更自然的变体处理

### 4. 普通正文页还不够丰富

当前普通正文页主要还是双栏模式，虽然比之前大很多，但仍然有限：

- 还缺更强的左右图文切换
- 还缺更成熟的“标题 + 小点 + 重点高亮”组合
- 还没有真正按内容类型自动决定图文比例

### 5. 图表页、时间线页、对照页仍需继续深挖

目前虽然已有基本模板入口，但仍偏初级：

- 图表页还没有完整的数据语义驱动
- 时间线页还不够像成熟咨询汇报
- 对照页还缺结构化列头、标签、指标层级

### 6. 图片素材策略还比较粗

当前图片策略是先保证可下载、可插入、可显示。

但仍缺：

- 更强的图片审美筛选
- 更稳定的封面图质量判别
- 版式和图片内容之间更紧的匹配关系

### 7. 还没有进入真正的 PPT 迭代链路

当前主要完成的是“首次生成链路”。

还没有完整收口到：

- 读取已有飞书 PPT
- 定位指定 slide / 指定元素
- 做增量修改
- 替换已有图片
- 局部重写某一页 XML
- 保留已有结构并只修改用户指定部分

这将是下一阶段的重点。

## 当前 MVP 的边界结论

当前可以认为已经完成：

- 飞书原生 PPT 首次生成链路可跑通
- 能插入真实图片
- 已初步做到图文分离
- 已具备目录页、过渡页、感谢页等基本页面语义
- 后续可以基于现有 IR 和 pageType 体系继续做 PPT 迭代修改

但当前还不能认为已经完成：

- 成品级精致视觉设计
- 稳定高质量的多版式自动设计
- 面向复杂用户指令的精细 PPT 局部修改

## 下一阶段：转入 PPT 迭代链路

下一阶段建议目标不是继续堆首次生成模板，而是转向“已有 PPT 的增量修改能力”，重点包括：

- 读取已有飞书 PPT 内容与 XML
- 建立 slide / element 的稳定定位方式
- 支持替换某页图片、替换某页文案、重生成某一页
- 区分首次生成链路与迭代修改链路
- 继续复用当前的 `PresentationSlideIR` / `pageType` / `section` 语义

这样后续用户才可以从：

- “帮我生成一份 PPT”

过渡到：

- “把第 3 页改成时间线”
- “把封面图替换成更正式的商务风”
- “把目录缩成 4 个小点”
- “只改第二章，不动其他页面”

这也是当前 MVP 阶段完成后，最值得投入的下一步。
