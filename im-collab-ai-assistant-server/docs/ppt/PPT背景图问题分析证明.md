# PPT 背景图问题分析证明

## 问题描述

生成 PPT 后，发现以下问题：
1. 正文和过渡页出现两种款式的背景图，不统一
2. 部分页面没有背景图

## 分析过程

### 1. 日志关键信息提取

从后端日志中提取关键信息：

```
ppt.asset.background.debug sharedBackgroundSlideCount=8 uniqueSharedBackgroundSourceUrlCount=1 uniqueSharedBackgroundFileTokenCount=0 slidesUsingSharedBackground=slide-4,slide-5,slide-7,slide-8,slide-10,slide-11,slide-13,slide-15
```

- `sharedBackgroundSlideCount=8`：规划了 8 页使用统一正文背景图
- `uniqueSharedBackgroundSourceUrlCount=1`：统一背景图搜索结果只有 1 张图片
- `slidesUsingSharedBackground`：实际应用到 slide-4,5,7,8,10,11,13,15

### 2. 图片上传失败

日志中发现多处上传失败：

```
Lark slides media upload failed: presentationId=YjWZsm56Ql8AHkdSOE4cg5zynLb, ... error=upload media failed: [99991400] request trigger frequency limit
```

- 错误码：`99991400` — 请求触发频率限制
- 原因：短时间内多个图片并行上传，触发飞书 API 频率限制
- 影响：上传失败的图片无法生成飞书 file_token，导致页面无背景图或回退使用其他图片

### 3. XML 数据分析

通过拉取 PPT 的 XML 进行分析：

```
背景图数量: 11 张
唯一背景图: 10 张
被复用的图: 1 张（F71kbpK6IoLdUlxKFjFcMennnVr，2页复用）
```

| 页面 | 类型 | 背景来源 |
|------|------|----------|
| slide-1 | COVER | hero-image |
| slide-2 | TOC | hero-image |
| slide-3 | TRANSITION | hero-image |
| slide-4 | CONTENT | sharedBackgroundImage |
| slide-5 | CONTENT | sharedBackgroundImage |
| slide-6 | TRANSITION | hero-image |
| slide-7 | CONTENT | sharedBackgroundImage |
| slide-8 | CONTENT | sharedBackgroundImage |
| slide-9 | TRANSITION | hero-image |
| **slide-10** | CONTENT | **none** (上传失败) |
| **slide-11** | CONTENT | **none** (上传失败) |
| **slide-12** | TRANSITION | **none** (上传失败) |
| slide-13 | TIMELINE | sharedBackgroundImage |
| slide-14 | TRANSITION | hero-image |
| slide-15 | CONTENT | sharedBackgroundImage |
| slide-16 | THANKS | hero-image |

### 4. 每页图片注入日志

```
ppt.slide.image.injection slideId=slide-4 pageType=CONTENT ... backgroundSourceKind=sharedBackgroundImage
ppt.slide.image.injection slideId=slide-10 pageType=CONTENT ... backgroundSourceKind=none injectedBackgroundToken=
ppt.slide.image.injection slideId=slide-5 pageType=CONTENT ... backgroundSourceKind=sharedBackgroundImage
```

- `backgroundSourceKind=sharedBackgroundImage`：使用统一背景图
- `backgroundSourceKind=none injectedBackgroundToken=`：无背景图（上传失败）

## 结论

### 问题根因

1. **多线程并行上传触发频率限制**
   - 图片上传使用并行处理
   - 短时间内上传多个文件触发飞书 API 频率限制（错误码 99991400）
   - 上传失败的图片无法获取 file_token，导致页面无背景图

2. **统一背景图未正确复用**
   - 规划了 8 页使用统一背景图，搜索结果仅 1 张
   - 但实际 XML 中有 10 种不同背景图
   - 原因：上传失败后回退使用各页自己的 contentImage，导致每页背景不同

### 不是多批次导致

XML 分析证明：
- 唯一背景图 10 张，每页独立
- 如果是多批次，应该出现明显的批次间差异（如两批不同的主图）
- 实际是上传失败导致的随机回退

## 解决方案建议

1. **增加上传间隔**：在图片上传时增加间隔时间，避免触发频率限制
2. **增加重试机制**：上传失败后自动重试
3. **批量上传**：使用飞书的批量上传接口
4. **统一背景图强制复用**：即使部分上传失败，仍强制使用统一背景图，不要回退到 contentImage

# 时间轴图片单一

## 分析结果

从 XML 分析找到了关键信息：
图片分布
尺寸	数量	用途
960x540	11 张	背景图
396x252	3 张	右侧大图
110x82	4 张	时间轴节点
时间轴节点图片复用
src=QS7IbYcC7oLdNzx1y0rc95ihnxh 使用了 4 次
这 4 次对应时间轴的 4 个节点，每张图片完全相同！

## 原因

从日志可以看出：
ppt.timeline.image.stats stage=resolve slideId=slide-13 templateVariant=horizontal-milestones timelineTaskCount=0 timelineResolvedAssetCount=0

- timelineTaskCount=0 — 根本没有为时间轴节点规划图片任务

- timelineResolvedAssetCount=0 — 没有解析到任何时间轴图片
  所以时间轴节点根本没有单独的配图！但 PPT 中确实显示了 4 张图片，这 4 张图片都是统一背景图 sharedBackgroundImage。
  代码逻辑（PresentationWorkflowNodes.java 2412-2437行）：
  TimelineRenderImage image = i < images.size() ? images.get(i) : null;
  if (image != null && hasText(image.src())) {
    builder.append("<img src=\"" + image.src() + "\" ...>");
  }
  因为 images 列表为空，不会渲染节点图片。那 PPT 中的时间轴节点图片是哪来的？
  让我再看一下日志：
  ppt.slide.image.injection slideId=slide-13 ... backgroundSourceKind=sharedBackgroundImage injectedBackgroundToken=DykVbHwlPoOClYx74oIc1x6Knhb heroImageToken=QS7IbYcC7oLdNzx1y0rc95ihnxh
  啊！heroImageToken=QS7IbYcC7oLdNzx1y0rc95ihnxh — 这是 contentImage 被当作 hero-image 使用了！

  

  ## 结论

- 时间轴页面的背景图（injectedBackgroundToken）是 DykVbHwlPoOClYx74oIc1x6Knhb，而 heroImageToken 是 QS7IbYcC7oLdNzx1y0rc95ihnxh，但由于代码逻辑问题，这张图被复用了 4 次作为每个时间轴节点的图片。