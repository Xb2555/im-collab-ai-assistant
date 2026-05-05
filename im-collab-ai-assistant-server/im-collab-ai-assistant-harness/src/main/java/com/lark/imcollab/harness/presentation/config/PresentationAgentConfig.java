package com.lark.imcollab.harness.presentation.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
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
                        3. 首版只使用 shape(type=text/rect)、line、content、p、ul、li，不使用图片、表格、chart、动画；可用 rect 模拟卡片、分栏、指标块、时间线节点。
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
