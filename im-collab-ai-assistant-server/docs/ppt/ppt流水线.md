# 飞书原生 PPT 页面体系与样式引擎重构方案

  ## Summary

目标是保留现有 graph 编排主链和飞书原生 Slides 交付，重构页面语义、模板渲染、图片布局和 prompt 管理，让生成结果从“固定模板上贴图”升级为“按页面类型生成成品级演示稿”。

  第一阶段优先级按你的选择固定为：

  - 最终产物必须是飞书原生 PPT（便于后续PPT迭代功能的扩展）
  - 先做可扩展架构，再用该架构承载精致观感
  - 目录页驱动章节过渡页
  - 页面建模做到“类型 + 子类型”
  - 图表/时间线继续走原生 XML 绘制
  - 外部 API 只作为调研备选，不作为主生成链路

  ## Key Changes

  ### 1. 建立新的页面语义体系

  扩展当前 PresentationSlidePlan 的粗粒度 layout/templateVariant，新增显式页面类型与子类型字段，至少覆盖：

  - 一级类型：
      - COVER
      - TOC
      - TRANSITION
      - BACKGROUND
      - CONTENT
      - CHART
      - TIMELINE
      - COMPARISON
      - THANKS
  - 二级子类型：
      - CONTENT.USER_INTENT_BACKGROUND
      - CONTENT.HALF_IMAGE_HALF_TEXT
      - CONTENT.BULLET_EXPLANATION
      - CHART.LINE
      - CHART.PIE
      - TIMELINE.HORIZONTAL_ARROW
      - COMPARISON.COMPETITOR_ANALYSIS
      - TRANSITION.SECTION_BREAK
      - THANKS.CLOSING

  同时给每页补章节归属字段，用于目录和过渡页联动：

  - sectionId
  - sectionTitle
  - sectionOrder

  ### 2. 重构大纲生成逻辑，让目录页驱动过渡页

  storyline -> outline 阶段改为先产出章节结构，再展开页面：

  - 大纲先生成章节目录，每个章节至少有：
      - sectionId
      - sectionTitle
      - goal
      - pages
  - 自动生成一页目录页
  - 每个章节自动生成一页过渡页
  - 正文页归属到某个章节
  - 过渡页文字只随 sectionTitle 和章节一句摘要变化，版式固定统一
  - THANKS 固定作为最后一页，不再复用 summary 模板冒充结尾页

  页数分配规则在第一阶段固定为：

  - COVER 1 页
  - TOC 1 页
  - 每个一级章节 1 页 TRANSITION
  - 正文内容页按材料密度展开
  - THANKS 1 页

  ### 3. 把 prompt 从代码中解耦到 resources/prompt/ppt-style/

  将 PresentationAgentConfig 中硬编码的 system prompt 下沉到资源目录，至少拆成：

  - storyline-system-prompt.txt
  - outline-system-prompt.txt
  - image-plan-system-prompt.txt
  - slide-xml-system-prompt.txt
  - review-system-prompt.txt

  并新增页面类型参考 prompt/规范文件，例如：

  - page-taxonomy.md
  - page-style-rules.md
  - section-toc-rules.md
  - image-layout-rules.md

  代码只负责加载 prompt 资源，不再内嵌长文本规则。这样后续调样式、调页面类型、调语言规则都不需要改 Java。

  ### 4. 把“模板 + 图片”从后贴图改成版式驱动渲染

  当前问题根因是图片在 IR 和 XML 阶段统一用固定坐标后插入。要改成“页面模板决定图片区和文字安全区”：

  - COVER：
      - 全屏或半屏背景图
      - 固定加遮罩层
      - 标题/副标题/摘要放在安全区内
  - TOC：
      - 不插大图
      - 章节列表统一样式
  - TRANSITION：
      - 不插复杂图片
      - 大号章节标题 + 小号引导语
  - CONTENT.HALF_IMAGE_HALF_TEXT：
      - 左文右图或左图右文二选一
      - 文字区与图片区严格分栏
      - 小点标题加粗/强调色，正文常规色
  - BACKGROUND：
      - 偏“用户意图/需求简述”，图弱化或不用图
  - CHART.LINE / CHART.PIE：
      - 图表区和说明区固定
      - 继续原生 XML 画图，不图片化
  - TIMELINE.HORIZONTAL_ARROW：
      - 固定水平箭头主轴
      - 节点均分布局
  - COMPARISON.COMPETITOR_ANALYSIS：
      - 双列或三列对照卡片
  - THANKS：
      - 固定版式，只替换结束文案

  实现上不再允许 compileSlideXml() 用统一 <img> 片段 replace("</data>", ...) 追加到页面尾部。改为每类页面模板自行输出图层顺序、图片区坐标、遮罩、文字安全区，图片作为模板内原生元素参与生成。

  ### 5. 重构图片版式与资源语义

  图片资源不只区分 image/illustration，还要区分用途语义：

  - BACKGROUND_HERO
  - HALF_SCREEN_VISUAL
  - CARD_THUMBNAIL
  - SECTION_DECORATION
  - CHART_SUPPORT_VISUAL

  presentationImagePlannerAgent 产出的任务中补充：

  - visualRole
  - placementHint
  - cropMode
  - priority

  buildSlideIr() 不再把所有图片统一塞成 hero-image，而是按页面子类型映射成不同 element role，例如：

  - cover-background
  - right-visual
  - left-visual
  - card-image
  - section-accent

  ### 6. 新建样式模板层，替代“布局名 + 少量变体”的弱表达

  保留现有 layout/templateVariant 兼容层，但主语义切换为“页面类型模板”：

  - 每个一级/二级页面类型有独立模板构建器
  - 每个模板构建器负责：
      - 背景
      - 图文比例
      - 安全区
      - 标题层级
      - 颜色系统
      - 图片插入位置
      - 图表/时间线结构
  - buildSlideXmlTemplate() 由单一大 switch，演进为：
      - 页面类型路由
      - 子类型模板构建
      - 样式配置注入

  第一阶段不强求引入复杂继承层次，推荐：

  - 页面语义模型用组合
  - 模板渲染器用接口 + 多实现
  - 样式配置单独对象
    这样比深继承更稳，更适合后续扩页面类型

  ### 7. 图表与时间线保持飞书原生 XML

  第一阶段不走外部图表图片化：

  - CHART.LINE：
      - 固定 2-4 个序列以内
      - 数据不足时降级为趋势卡片
  - CHART.PIE：
      - 固定 3-6 个分区
      - 数据不足时降级为占比说明卡片
  - TIMELINE.HORIZONTAL_ARROW：
      - 统一主轴箭头
      - 节点均匀分布
      - 节点标题与说明两层文字

  如果上游没有结构化数据，允许大纲阶段只规划“图表表达意图”，由系统生成示意性图表，不臆造具体业务数值；若无可靠数据则自动降级为非图表正文页。

  ### 8. 外部 API 调研只保留为备选预案

  已知可调研方向是 Gamma 这类官方 API，可程序化生成 presentation 并导出 PPTX/PDF。但在本期约束下：

  - 不作为主链路
      - 若未来允许“PPTX 先生成再导入飞书”，可再评估
      - 当前不进入实现主干，不引入新依赖、不改交付定义

  1. 页面建模测试

  - PresentationSlidePlan 新字段能正确序列化/反序列化
  - 章节结构能稳定展开为 COVER + TOC + TRANSITION + CONTENT... + THANKS

  2. 大纲生成测试

  - 给定 3 个章节时，自动生成 1 页目录页和 3 页过渡页
  - 过渡页版式相同，仅文字按章节变化
  - BACKGROUND / CONTENT / CHART / TIMELINE / COMPARISON 能按输入意图正确分型

  3. 模板渲染测试

  - COVER 图片不会压住标题文字
  - CONTENT.HALF_IMAGE_HALF_TEXT 图片和正文不重叠
  - TOC 与 TRANSITION 不引入无意义大图
  - THANKS 始终使用专属模板，不复用 summary
  - CHART.LINE、CHART.PIE、TIMELINE.HORIZONTAL_ARROW 输出合法 XML 结构

  4. 图片布局测试

  - 同一张图在不同页面类型下会映射到不同 role 和坐标
  - compileSlideXml() 不再通过统一尾插方式加图
  - 图片失败时页面可降级，但不破坏文字布局

  5. Prompt 解耦测试

  - 所有 agent prompt 从 resources/prompt/ppt-style/ 成功加载
  - 缺文件时能明确失败，不静默回退到硬编码字符串

  6. 回归测试

  - 保留现有飞书创建、媒体上传、逐页写入测试
  - 增加 1 个端到端样例：目录驱动过渡页 + 半图半文正文 + 时间线页 + 感谢页

  ## Assumptions

  - 当前实现继续以 PresentationWorkflowNodes 为 graph 编排核心，不拆出新模块。
  - 第一阶段不引入第三方“直接生成 PPT”的生产依赖；外部 API 仅做备选预案，不进入主链路。
  - 页面样式目标优先参考“成品演示稿”的信息分层与版式逻辑，而不是逐像素模仿 Kimi。
  - 图表和时间线继续要求飞书原生可编辑，因此不采用截图/图片嵌入作为主实现。
  - 组合优先于深继承：页面实体可以有类型/子类型/章节/视觉配置组合字段，模板渲染器按类型分发实现。