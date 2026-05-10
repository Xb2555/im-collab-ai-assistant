# 飞书幻灯片 XML 能力分析

## 1. 最小操作粒度

| 层级 | 元素 | 说明 |
|------|------|------|
| presentation | 整份 PPT | 设置宽高 (width/height) |
| slide | 单页幻灯片 | 每页独立背景、样式 |
| data | 页面元素容器 | 放置 shape、img、table、chart 等 |
| shape | 形状元素 | 文本框、矩形、椭圆、线条等 |
| content | 文本内容 | 段落、列表、链接、加粗、斜体 |

### 支持的元素类型 (data 下)

```
shape      - 形状 (text/rect/ellipse/line 等)
img       - 图片 (通过 URL 或 file token 引用)
table     - 表格
chart     - 图表 (column/line/pie/radar 等)
icon      - 图标 (iconpark 图标集)
line      - 线条
polyline  - 折线
undefined - 占位符
```

## 2. XML 能干什么

### 可以做到的

1. **文本排版**
   - 标题、正文、caption 三种文本类型
   - 加粗、斜体、下划线、删除线
   - 链接 (a href)
   - 无序列表 (ul > li)、有序列表 (ol > li)
   - 行间距、文本对齐

2. **图形绘制**
   - 矩形、椭圆、线条
   - 纯色填充、渐变填充 (linear-gradient / radial-gradient)
   - 边框样式 (颜色、宽度、虚线)

3. **图片插入**
   - 通过 URL 或飞书 file_token 引用图片
   - 设置位置、尺寸、透明度 (alpha)

4. **图表 (chart)**
   - 柱状图、折线图、饼图、雷达图
   - 设置数据、颜色、标题

5. **表格 (table)**
   - 设置行列、列宽、合并单元格
   - 表头深色背景

6. **图标 (icon)**
   - 引用 iconpark 图标集

7. **背景设置**
   - 纯色背景、渐变背景

8. **备注 (note)**
   - 演讲者备注

### 不支持的 (关键差异)

```
❌ 无法执行 JavaScript
❌ 无法嵌入 iframe
❌ 无法渲染 HTML/CSS
❌ 无法动态计算布局
❌ 无法使用 CSS 动画
❌ 无法使用 Flexbox/Grid 布局
❌ 无法引用外部字体
❌ 无法使用 CSS 伪元素 (::before/::after)
❌ 无法媒体查询响应式布局
❌ 无法嵌入 Web 组件
```

## 3. 跟 HTML 的核心区别

| 特性 | 飞书 XML | HTML |
|------|----------|------|
| 布局方式 | 绝对坐标 (topLeftX, topLeftY, width, height) | Flexbox/Grid/Block |
| 渲染引擎 | 飞书服务端 SML 2.0 | 浏览器 WebKit |
| 样式系统 | 有限属性 (fill, border, font) | 完整 CSS (600+ 属性) |
| 脚本能力 | 无 | JavaScript 可执行 |
| 响应式 | 无 | 媒体查询 |
| 字体 | 系统预设 + iconpark | @font-face 自定义 |
| 动画 | 无 | CSS Animation/Transition |
| 交互 | 静态展示 | 动态交互 |

### 具体示例对比

#### 布局

**HTML** (Flexbox):
```html
<div style="display: flex; justify-content: center; align-items: center;">
  <h1>标题</h1>
</div>
```

**飞书 XML** (必须计算坐标):
```xml
<shape type="text" topLeftX="380" topLeftY="200" width="200" height="50">
  <content textType="title" textAlign="center">
    <p>标题</p>
  </content>
</shape>
```

#### 渐变背景

**HTML**:
```css
background: linear-gradient(135deg, #1e3a8a 0%, #3b82f6 100%);
```

**飞书 XML**:
```xml
<fill>
  <fillColor color="linear-gradient(135deg,rgba(30,60,114,1) 0%,rgba(59,130,246,1) 100%)"/>
</fill>
```
> 注意：飞书要求 rgba 格式 + 百分比停靠点，比 CSS 严格得多

#### 动画

**HTML**:
```css
@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}
```

**飞书 XML**: ❌ 完全不支持

## 4. 对"AI 生成精美 PPT"方案的评估

### 方案一：HTML 截图 -> 飞书图片 PPT ✅ 可行

```
Agent 生成 HTML -> 浏览器截图 -> 飞书图片上传 -> PPT
```

**优点**：
- 几乎可以获得任意精美效果
- 支持完整 CSS
- 支持动画 (截图静态)

**缺点**：
- 生成的 PPT 无法编辑
- 每页是一张图片
- 编辑能力丧失

### 方案二：HTML 转飞书 XML ❌ 不可行

```
Agent 生成 HTML -> 转译为飞书 XML
```

**原因**：
- HTML 太灵活，无法一一对应
- CSS 布局无法映射到绝对坐标
- 动画、交互完全丢失
- 需要大量人工兜底

### 方案三：直接生成飞书 XML ✅ 当前方案

```
Agent 直接生成 XML -> 飞书 API 创建 PPT
```

**优点**：
- 生成的 PPT 可编辑
- 文本可修改
- 图片可替换

**缺点**：
- 能力受限（见上文"不支持"列表）
- 难以实现"精美"效果
- 布局需要精确计算坐标

### 方案四：混合方案（推荐）

```
AI 生成两个版本：
1. 可编辑版：直接飞书 XML（文字为主）
2. 展示版：HTML 截图（图表/装饰为主）
```

用户需要编辑时用版本 1，需要精美展示时用版本 2。

## 5. 结论与建议

**飞书 PPT 不支持渲染 HTML**，只能通过 XML 定义静态页面元素。

### 当前可行的"精美"路径

1. **HTML + 截图** = 图片 PPT（不可编辑但精美）
2. **飞书 XML** = 可编辑 PPT（能力受限）

### 若要兼顾"精美"和"可编辑"

建议采用混合方案：
- 重要文字用飞书 XML
- 复杂图表/装饰用 HTML 截图

### 若要完全放弃可编辑性

直接走 HTML 截图方案，可以获得 CSS 完全体能力，做出任意的精美效果。

---

# 飞书 Slides XML 图片插入核心机制

1. <img> 元素完整属性（基于 XSD 2.0）
  <img src="file_token"
    topLeftX="100" topLeftY="120"
    width="320" height="180"
    rotation="0" flipX="false" flipY="false"
    alpha="1"
    exposure="0" contrast="0" saturation="0" temperature="0"
    alt="描述"/>
  必需属性：src、topLeftX、topLeftY、width、height。
  可选子元素：<crop>、<border>、<shadow>、<reflection>、<fill>。
2. LLM 生成 PPT 并成功渲染到飞书的完整流程
  步骤	操作	说明
  1	下载/生成图片到本地	PNG/JPG，最大 20MB
  2	上传到飞书 Drive	slides +media-upload --file ./xxx.png --presentation $PID，返回 boxcn... 格式的 file_token
  3	生成 Slide XML	在 <img src="file_token"> 中使用该 token，禁止使用 http(s):// 外链
  4	调用 API 创建/更新幻灯片	xml_presentation.slide.create 或 +replace-slide
  关键约束：
- src 只能接收 file_token（boxcn... 格式），禁止外链 URL
- width/height = 原图比例才不会裁剪
- +create --slides 支持 @./local.png 占位符自动上传，适合新建 PPT 带图
- +media-upload 用于给已有 PPT 的已有页加图（配合 +replace-slide 整页替换）
- 坐标系统以左上角为原点，topLeftX/topLeftY 指定图片左上角在 960×540 画布中的位置
3. 已有 PPT 已有页加图的工作流
  由于 XML API 无元素级编辑（无法在页中单独插入一个 <img> 元素），必须整页替换：
  fetchSlide → 解析 slide XML → 追加/替换 <img src="file_token"> → +replace-slide 整页替换
4. 关键提示给 LLM
  LLM 生成飞书 PPT XML 时必须遵循：
1. 使用 xmlns="http://www.larkoffice.com/sml/2.0" 命名空间
2. 所有图片必须用 boxcn... 格式 file_token，禁止直接嵌入 URL
3. 颜色格式用 rgb(r,g,b) 或 rgba(r,g,b,a)，渐变需用 rgba + 百分比停靠点
4. 标准 16:9 尺寸 width="960" height="540"
5. img 的 width/height 比例与原图一致才不会裁剪
测试文件已创建在 im-collab-ai-assistant-harness/src/test/java/com/lark/imcollab/harness/presentation/SlidesImageInsertTest.java，覆盖：media-upload 流、img 元素 XML 结构、完整带图幻灯片模板、多图卡片布局、坐标系统验证。