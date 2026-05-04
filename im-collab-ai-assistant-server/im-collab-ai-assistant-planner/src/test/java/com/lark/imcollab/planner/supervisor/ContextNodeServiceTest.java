package com.lark.imcollab.planner.supervisor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContextNodeServiceTest {

    @Test
    void extractsKeywordFromHistoricalDiscussionRequest() {
        assertThat(ContextNodeService.extractSearchQuery(
                "@飞书IM- test 整理一下之前历史消息中有关采购评审的讨论，输出一份采购分析文档，并生成评审ppt"
        )).isEqualTo("采购评审");
    }

    @Test
    void extractsKeywordFromAboutDiscussionRequest() {
        assertThat(ContextNodeService.extractSearchQuery("帮我整理一下关于供应商准入的消息"))
                .isEqualTo("供应商准入");
    }

    @Test
    void extractsKeywordWhenPointInTimeIsPresent() {
        assertThat(ContextNodeService.extractSearchQuery("拉取10分钟前关于采购评审的讨论"))
                .isEqualTo("采购评审");
    }
}
