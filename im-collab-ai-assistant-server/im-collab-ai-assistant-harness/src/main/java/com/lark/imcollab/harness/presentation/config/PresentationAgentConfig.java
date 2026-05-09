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
                .systemPrompt(prompt("ppt-style/storyline-system-prompt.txt"))
                .outputType(PresentationStoryline.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationOutlineAgent")
    public ReactAgent presentationOutlineAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-outline-agent")
                .description("Scenario D: generate slide outline")
                .systemPrompt(prompt("ppt-style/outline-system-prompt.txt"))
                .outputType(PresentationOutline.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationImagePlannerAgent")
    public ReactAgent presentationImagePlannerAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-image-planner-agent")
                .description("Scenario D: plan safe free-commercial image resources for slides")
                .systemPrompt(prompt("ppt-style/image-plan-system-prompt.txt"))
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
                .systemPrompt(prompt("ppt-style/slide-xml-system-prompt.txt"))
                .outputType(PresentationSlideXmlBatch.class)
                .model(chatModel)
                .build();
    }

    @Bean(name = "presentationReviewAgent")
    public ReactAgent presentationReviewAgent(ChatModel chatModel) {
        return ReactAgent.builder()
                .name("presentation-review-agent")
                .description("Scenario D: review presentation coverage and slide quality")
                .systemPrompt(prompt("ppt-style/review-system-prompt.txt"))
                .outputType(PresentationReviewResult.class)
                .model(chatModel)
                .build();
    }

    private String prompt(String name) {
        try (var stream = getClass().getClassLoader().getResourceAsStream("prompt/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("Missing prompt resource: " + name);
            }
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Failed to load prompt resource: " + name, exception);
        }
    }
}
