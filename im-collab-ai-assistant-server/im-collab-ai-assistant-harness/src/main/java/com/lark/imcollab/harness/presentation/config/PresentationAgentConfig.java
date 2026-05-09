package com.lark.imcollab.harness.presentation.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.lark.imcollab.harness.presentation.model.PresentationImagePlan;
import com.lark.imcollab.harness.presentation.model.PresentationImageResources;
import com.lark.imcollab.harness.presentation.model.PresentationOutline;
import com.lark.imcollab.harness.presentation.model.PresentationReviewResult;
import com.lark.imcollab.harness.presentation.model.PresentationSlideXmlBatch;
import com.lark.imcollab.harness.presentation.model.PresentationStoryline;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PresentationAgentConfig {

    @Bean(name = "presentationStorylineAgent")
    public ReactAgent presentationStorylineAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-storyline-agent")
                .description("Scenario D: generate a presentation narrative storyline")
                .systemPrompt("""
                        你负责把任务材料转成汇报型 PPT 的叙事主线。
                        输出结构化 JSON：
                        - title: PPT 标题
                        - audience: 受众
                        - goal: 汇报目标
                        - narrativeArc: 叙事结构
                        - style: 视觉风格
                        - pageCount: 建议页数
                        - sourceSummary: 输入材料摘要
                        - keyMessages: 3-7 条核心信息
                        规则：
                        1. 优先遵守系统给出的生成参数，尤其是 pageCount、style、audience、density；最多 10 页。
                        2. PPT 是演示稿，不是长文档。每页只承载一个中心观点。
                        3. 必须忠实使用输入材料，不能补编材料中没有的业务事实、数据或结论。
                        4. 如果上游有文档产物摘要，PPT 要基于文档要点提炼，而不是照搬长段落。
                        5. 标题要贴合用户任务，不要输出“汇报 PPT 初稿”这类空泛标题。
                        6. style 只从以下稳定风格里选择或贴近表达：deep-tech、business-light、fresh-training、vibrant-creative、minimal-professional。
                        """)
                .outputType(PresentationStoryline.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationOutlineAgent")
    public ReactAgent presentationOutlineAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-outline-agent")
                .description("Scenario D: generate slide outline")
                .systemPrompt("""
                        你负责生成 PPT 页面结构。
                        输出结构化 JSON：
                        - title: PPT 标题
                        - audience: 受众
                        - style: 风格
                        - slides: 页面数组，每页包含 slideId、index、title、keyPoints、layout、templateVariant、visualEmphasis、speakerNotes
                        规则：
                        1. slides 页数必须等于叙事主线 pageCount，且 1 <= 页数 <= 10。
                        2. 根据 density 控制信息密度：concise 每页 2-3 条，standard 每页 3-4 条，detailed 每页 4-5 条；每条不超过 42 个中文字符；技术枚举、接口名、状态名必须完整保留。
                        3. layout 只能使用稳定布局：cover、section、two-column、summary、timeline、risk-list、metric-cards、comparison。
                        4. templateVariant 必须从以下稳定集合中选择：
                           - cover: hero-band、center-stack、asymmetric-title
                           - section: headline-panel、rail-notes、split-band
                           - two-column/comparison: dual-cards、offset-columns
                           - timeline: horizontal-milestones、stacked-steps
                           - metric-cards/risk-list: top-stripe-cards、compact-grid、spotlight-metric
                           - summary: closing-checklist、next-step-board
                        5. visualEmphasis 只允许：title、balance、data、action。
                        6. speakerNotes 写给演讲者，简短说明页面表达重点。
                        7. 禁止把文档原文长段落塞入 keyPoints。
                        8. keyPoints 必须是完整短句，不要使用省略号、半句话或被截断的词。
                        9. 页面结构要多样化；不要除封面外所有页面都用同一种 layout。
                        10. 同一份 PPT 至少使用 2 种正文 templateVariant；封面和总结页使用各自独立变体。
                        """)
                .outputType(PresentationOutline.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationImagePlannerAgent")
    public ReactAgent presentationImagePlannerAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-image-planner-agent")
                .description("Scenario D: plan safe free-commercial image resources for slides")
                .systemPrompt("""
                        你负责为 PPT 每一页规划图片、插画和图表资源。
                        只返回 JSON，不要解释。

                        目标：
                        - 内容图片用于佐证和丰富页面
                        - 插画用于装饰和概念表达
                        - 图表用于流程、关系、指标表达

                        素材许可约束：
                        - 只使用免费可商用优先来源
                        - 内容图优先来源：unsplash.com, pexels.com, pixabay.com
                        - 插画优先来源：undraw.co, storyset.com, manypixels.co, svgrepo.com
                        - 图表不走图片搜索，输出 mermaidCode

                        规划规则：
                        1. 每页 0-2 个图片任务
                        2. visualEmphasis=balance 时优先内容图
                        3. visualEmphasis=action 时优先插画
                        4. visualEmphasis=data 时优先 diagramTasks
                        5. query 用英文优先，必要时可包含中文补充
                        6. purpose 必须说明图片放在哪、解决什么表达问题

                        输出 schema:
                        {
                          "pagePlans": [
                            {
                              "slideId": "slide-1",
                              "contentImageTasks": [
                                {
                                  "query": "retail store interior modern china",
                                  "purpose": "用于右侧主视觉，展示门店场景",
                                  "preferredSourceType": "CONTENT_IMAGE",
                                  "preferredDomains": ["pexels.com","unsplash.com"]
                                }
                              ],
                              "illustrationTasks": [
                                {
                                  "query": "workflow collaboration illustration",
                                  "purpose": "用于左下角辅助说明协作流程",
                                  "preferredSourceType": "ILLUSTRATION",
                                  "preferredDomains": ["undraw.co","storyset.com"]
                                }
                              ],
                              "diagramTasks": [
                                {
                                  "mermaidCode": "flowchart LR\\nA-->B",
                                  "purpose": "用于表达流程"
                                }
                              ]
                            }
                          ]
                        }
                        """)
                .outputType(PresentationImagePlan.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationImageFetcherAgent")
    public ReactAgent presentationImageFetcherAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-image-fetcher-agent")
                .description("Scenario D: fetch candidate free-commercial image resource urls")
                .systemPrompt("""
                        你负责根据图片规划返回可用的素材候选 URL。
                        只返回 JSON，不要解释。

                        约束：
                        - 只允许这些站点：unsplash.com, pexels.com, pixabay.com, undraw.co, storyset.com, manypixels.co, svgrepo.com
                        - 必须返回 https URL
                        - 不要返回搜索首页 URL，返回素材详情页或可下载直链
                        - 图片必须区分 contentImages / illustrations / diagrams
                        - diagrams 若无现成图片，whiteboardDsl 可为空，sourceUrl 可为空

                        输出 schema:
                        {
                          "resources": [
                            {
                              "slideId": "slide-1",
                              "contentImages": [
                                {
                                  "sourceUrl": "https://images.unsplash.com/...",
                                  "sourceSite": "unsplash.com",
                                  "assetType": "image",
                                  "purpose": "用于右侧主视觉"
                                }
                              ],
                              "illustrations": [
                                {
                                  "sourceUrl": "https://undraw.co/illustration.svg",
                                  "sourceSite": "undraw.co",
                                  "assetType": "illustration",
                                  "purpose": "用于左下角点缀"
                                }
                              ],
                              "diagrams": [
                                {
                                  "sourceUrl": null,
                                  "sourceSite": "mermaid",
                                  "assetType": "diagram",
                                  "purpose": "用于流程表达",
                                  "whiteboardDsl": ""
                                }
                              ]
                            }
                          ]
                        }
                        """)
                .outputType(PresentationImageResources.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationSlideXmlAgent")
    public ReactAgent presentationSlideXmlAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-slide-xml-agent")
                .description("Scenario D: generate Lark Slides XML for all slides in one batch")
                .systemPrompt("""
                        你负责一次性为整份 PPT 生成飞书 Slides XML。
                        输出结构化 JSON：
                        - slides: 页面数组，数组长度必须等于输入的页面计划数量
                        - 每个 slides[] 元素包含 slideId、index、title、xml、speakerNotes
                        - 每个 xml 都必须是完整 <slide xmlns="http://www.larkoffice.com/sml/2.0">...</slide>
                        XML 规则：
                        1. 画布为 960x540，所有坐标必须在画布内。
                        2. slide 直接子元素只能使用 style、data、note。
                        3. 支持 shape(type=text/rect)、line、content、p、ul、li、img；不使用动画；图片必须使用 file_token。
                        4. 必须包含一个标题 text shape 和至少一个正文/要点 shape。
                        5. 不要输出 Markdown 代码块，不要返回 presentation 根元素。
                        6. 文本要短，适合投屏阅读。
                        7. 页面可见文本必须是完整短句，不要出现省略号或截断词。
                        8. 所有文字必须包在 content/p/span 或 content/ul/li/p/span 内，禁止把文字直接写成 shape 节点文本。
                        9. 必须按输入页面计划逐页生成，不得漏页、合并页或新增页。
                        10. xml 字段是 JSON 字符串，必须正确转义双引号和换行。
                        合法 XML 片段示例：
                        <slide xmlns="http://www.larkoffice.com/sml/2.0"><style><fill><fillColor color="rgb(255,255,255)"/></fill></style><data><shape type="text" topLeftX="64" topLeftY="48" width="820" height="88"><content textType="title"><p><strong><span color="rgb(20,35,60)" fontSize="32">示例标题</span></strong></p></content></shape><shape type="text" topLeftX="80" topLeftY="160" width="760" height="220"><content><ul><li><p><span color="rgb(20,35,60)" fontSize="20">示例要点</span></p></li></ul></content></shape></data></slide>
                        """)
                .outputType(PresentationSlideXmlBatch.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationReviewAgent")
    public ReactAgent presentationReviewAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-review-agent")
                .description("Scenario D: review presentation coverage and slide quality")
                .systemPrompt("""
                        你负责审查 PPT 初稿。
                        输出结构化 JSON：
                        - accepted: 是否可发布
                        - missingItems: 缺失项列表
                        - problemSlideIds: 有问题页面 ID
                        - summary: 审查结论
                        - revisionAdvice: 修改建议
                        规则：
                        1. 检查是否覆盖用户目标、受众、关键材料和上游文档要点。
                        2. 检查是否把文档长段落硬塞进 PPT。
                        3. 检查是否存在空页、过长页、主题漂移。
                        4. XML 可解析性由系统校验负责；你只审查内容和信息密度。
                        5. 若基本可用但仍有轻微措辞建议，accepted 可以为 true，并把建议写入 revisionAdvice。
                        """)
                .outputType(PresentationReviewResult.class)
                .model(chatModel)
                .build();
    }
}
