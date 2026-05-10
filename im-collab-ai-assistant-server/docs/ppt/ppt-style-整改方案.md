  # PPT 生成与迭代一步到位整改方案

  ## Summary

 目标是把当前 PPT 能力从“文本页生成器 + 粗粒度页码迭代”升级为“可带图、可做图表、可保持较高审美、且支持页内精确迭代的可编辑 PPT 系统”。主产物统一保留为飞书 Slides XML，不再把“精美”与“可编辑”拆成两套主链路。复杂视觉元素允许局部静态化为图片。

  整改后的系统分成两条共享底座、不同阶段的链路：

  - 生成链路：内容规划 -> 视觉规划 -> 资源规划/获取 -> 版式求解 -> XML 生成 -> 预检 -> 创建/回写
  - 迭代链路：意图解析 -> 页内锚点解析 -> 结构化 patch 规划 -> 精确替换/插入/移动 -> 目标态校验

  ## Key Changes

  ### 1. 统一 PPT 文档模型，替代“直接拼 XML”

  建立中间表示 PresentationIR，作为生成和迭代共享真相源，至少包含：

  - presentation 元信息：标题、主题、页面尺寸、主题色、字体策略
  - slide 列表：slideId、语义角色（cover/section/content/data/closing）
  - element 列表：elementId、elementKind（title/body/image/chart/icon/shape/table/note）、semanticRole、contentRef、layoutBox
  - 资源引用：assetId/fileToken/url/whiteboardDsl/chartData
  - 可编辑锚点：anchorTexts、bodyItems、originBlockId

  要求：

  - XML 生成不再直接由 LLM 输出整页 shape 拼装结果作为唯一真相
  - LLM 主要输出内容和视觉意图，布局与落点由程序完成
  - 迭代时先把现有 XML 逆向解析为 PresentationIR，再做 patch

  ### 2. 生成链路拆成 6 层，而不是“outline -> xml 一步到位”

  #### 2.1 Content Planning

  保留 storyline / outline，但输出收敛为结构化内容，不直接承诺 XML。
  每页至少产出：

  - slideId
  - slideRole
  - title
  - message
  - keyPoints
  - evidenceNeeds
  - visualIntent（hero-image / comparison / metric-cards / process / timeline / chart / mixed-media）
  - editPriorityZones（标题、正文、图表说明等）

  #### 2.2 Visual Planning

  新增视觉规划层，负责把内容页映射到稳定模板，不让 LLM 自算绝对坐标。
  产出：

  - templateVariant
  - density
  - imageSlots
  - chartSlots
  - iconSlots
  - bodySlotCount
  - backgroundStyle
  - accentStyle

  这里决定“这页该用双栏图文、数据卡片、流程图、单图封面”等，而不是让 XML agent 临场发挥。

  #### 2.3 Asset Planning / Acquisition

  把能力分析中提到的图片、插画、图表能力正式纳入主链路。
  新增资源规划与获取层：

  - 内容图：搜索词、用途、横竖比例、期望主体
  - 插画/装饰图：风格、透明背景需求、位置用途
  - 图表：直接结构化数据 -> chart XML；流程/架构图 -> Mermaid/whiteboard -> 导出图片或 DSL
  - 图标：iconpark 关键词与风格
  - Logo / 产品图 / 截图：可选 AI 生成或上传

  默认策略：

  - 能结构化编辑的，优先原生元素
      - 标题、正文、列表、简单卡片、表格、原生 chart
  - 难结构化编辑但对视觉要求高的，局部图片化
      - 复杂图解、装饰背景、大型视觉主图、复杂合成图
  - 整页截图仅作为最终降级
      - 当某页视觉复杂度超过模板系统能力时，单页允许图片化，但需显式标记为 slideMode=RASTERIZED

  #### 2.4 Layout Solver

  新增程序化布局求解器，负责：

  - 根据模板与内容长度计算 topLeftX/Y/width/height
  - 标题、正文、图片、图表之间的相对间距与安全边距
  - 自动裁剪策略与长文本溢出策略
  - 图片按比例放置，正文自动拆 bullets
  - 生成 element 级唯一语义 ID

  禁止继续由 LLM 直接给大量绝对坐标作为主方案。LLM 可提视觉意图，不负责像素级排版。

  #### 2.5 XML Compiler

  由 PresentationIR -> Slides XML 的编译器统一生成：

  - <slide>、<shape>、<img>、<table>、<chart>、<icon>、<note>
  - 所有图片统一先上传为 file_token，再写入 src
  - 复杂图解如果走图片化，在 IR 中标记来源和替换策略

  #### 2.6 Preflight / Render Verification

  在真正创建 PPT 前做本地预检：

  - XML 命名空间、必需属性、颜色格式、图片 token 格式
  - 元素越界检查、重叠检查、标题/正文溢出检查
  - 长文本分页或缩排策略
  - chart/table/img 的结构校验

  ### 3. 迭代链路升级为“页码 + 页内锚点 + 元素语义”三段式

  当前迭代最大问题是只按 pageIndex + TITLE/BODY 猜。整改后改为：

  #### 3.1 Presentation Edit Intent

  扩展 PPT 编辑意图模型，除 pageIndex 与 targetElementType 外，新增：

  - anchorMode: BY_PAGE_INDEX | BY_QUOTED_TEXT | BY_ELEMENT_ROLE | BY_BLOCK_ID
  - quotedText
  - elementRole
  - expectedMatchCount
  - operationType: rewrite / expand / shorten / replace / insert_after / move / delete
  - contentInstruction

  规则：

  - 用户说“第一页这段 xxx 写详细一些”，优先解析成 BODY + BY_QUOTED_TEXT
  - 用户只说“第一页标题改成 xxx”，才落 TITLE
  - 无法唯一定位时必须 clarificationNeeded=true，禁止再猜标题

  #### 3.2 Slide Snapshot Builder

  为每页建立结构快照，提取：

  - slideId
  - blockId/shapeId
  - elementKind
  - textType
  - textContent
  - normalizedText
  - boundingBox
  - semanticRole
  - editability（native/rasterized/generated-chart）

  这相当于文档迭代里的结构快照，只不过对象从 block 变成 slide element。

  #### 3.3 Anchor Resolver

  新增页内锚点解析器：

  - 先缩小到页
  - 再按 quoted text / role / title/body 类型在页内命中 element
  - 多候选时按文本相似度、文本类型、位置权重排序
  - 置信度不足直接追问，不执行

  要求明确支持：

  - “第一页这段……”
  - “第2页右侧那张图下面的说明”
  - “目录页第三条”
  - “第3页流程图改成采购 -> 评审 -> 执行”

  #### 3.4 Patch Planner

  根据目标 element 类型生成不同 patch：

  - title/body：block_replace
  - image：替换 file_token 或替换整个 <img>
  - chart：替换 chart data 或重编译 chart block
  - rasterized element：重新生成对应图片并替换 <img>
  - insert body paragraph/list：如果目标是正文 shape，则在 content 内增量重写，而不是盲替换标题

  #### 3.5 Target State Verification

  引入 PPT 版 target-state verify：

  - 校验目标 blockId/shapeId 是否仍存在或被合理替代
  - 校验 replacementText 是否出现在目标 element，而不是只看整页全文 contains
  - 对图像/图表类校验 file_token、chart signature、alt/caption
  - 对 rasterized page 校验 hash/assetRef/version marker

  ### 4. 明确“精美 + 可编辑”的页面分层策略

  每页必须显式标记一种模式：

  - NATIVE_EDITABLE
      - 标题、正文、卡片、简单图表、表格、图标、普通配图
      - 优先用于大多数业务汇报页
  - HYBRID_EDITABLE
      - 标题/正文可编辑，复杂视觉主体是 <img>
      - 这是推荐默认模式
      - 必须在 IR 和 artifact metadata 中标记，迭代时提示“该页仅支持整页重生成，不支持段落级编辑”

  默认采用 HYBRID_EDITABLE，不要把系统设计成“要么全可编辑但丑，要么全截图但不可编辑”。

  ### 5. 与当前代码的关键替换关系

  以行为替换为主，不做局部打补丁：

  - 用 PresentationIR + LayoutSolver + XmlCompiler 替换“SlideXml Agent 直接决定全部结构”的主职责
  - 用 SlideSnapshotBuilder + PresentationAnchorResolver + PresentationPatchPlanner + PresentationTargetStateVerifier 替换当前 pageIndex + TITLE/BODY 的粗粒度迭代逻辑
  - 保留 LarkSlidesTool 作为底层执行器，但扩展：
      - media upload
      - image replace
      - chart/table helper
      - fetch + reverse parse support

  ## Test Plan

  ### 生成链路

  - 纯文本商务汇报：生成 6-8 页，标题/正文可编辑
  - 图文混排页：一张配图 + 标题 + bullet，图片通过 file_token 正常渲染
  - 数据页：原生 chart 或图表图片混合生成，版式不溢出
  - 封面页：复杂背景/大图存在时，仍能保留主标题可编辑
  - 单页复杂视觉超限：正确降级为 RASTERIZED_PAGE

  ### 迭代链路

  - “把第一页标题改成 xxx” 只改 title，不碰正文
  - “第一页这段‘文旅融合创新，消费场景丰富多元’写详细一些” 命中 body，不改标题
  - “把第2页右侧图片换成门店实景图” 命中 image block
  - “把第3页流程图改成三阶段” 正确替换图表或图解资源
  - 对 RASTERIZED_PAGE 执行段落级编辑时，返回明确限制提示，不误改其他元素
  - 多候选页内文本匹配时，进入澄清，不默认命中 title

  ### 稳定性

  - 大 XML fetch 不再因 stdout 管道阻塞超时
  - 图片 token 丢失、chart 数据非法、元素越界等在 preflight 阶段拦截
  - 生成与迭代均带结构化日志：slideId、elementId、anchorMode、resolved blockId、patch type、verify result

  ## Assumptions

  - 这份能力分析可作为“Slides 是 XML/绝对坐标/静态渲染”的正确前提，但不能据此得出“精美只能整页截图”的结论
  - 主链路继续基于飞书 Slides XML 与 lark-cli，不改成导出本地 .pptx 再导入
  - 允许在页面内部对复杂视觉做局部图片化，但产品默认目标仍是可编辑 PPT
  - 整改应以“生成和迭代共用一套 IR/快照/锚点模型”为原则，避免再次出现生成一套、迭代另一套的双轨系统