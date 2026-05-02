package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocRefExtractionServiceTest {

    private final DocRefExtractionService service = new DocRefExtractionService(new ObjectMapper());

    @Test
    void extractsDocRefFromPlainText() {
        assertThat(service.extractDocRefs(
                "这个是文档链接：https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph",
                null
        )).containsExactly("https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph");
    }

    @Test
    void extractsDocRefFromNestedRawJson() {
        String raw = """
                {"text":"飞书项目协作技术方案","share_link":"https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph","meta":{"url":"https://example.larksuite.com/wiki/AbCdEf123"}}
                """;

        assertThat(service.extractDocRefs("飞书项目协作技术方案", raw))
                .containsExactly(
                        "https://jcneyh7qlo8i.feishu.cn/docx/B4jUdLQnFofWU7x6M8ycb6MAnph",
                        "https://example.larksuite.com/wiki/AbCdEf123"
                );
    }

    @Test
    void doesNotGuessDocRefFromTitleOnly() {
        assertThat(service.extractDocRefs("飞书项目协作技术方案这个是文档链接", "{\"text\":\"飞书项目协作技术方案\"}"))
                .isEmpty();
    }
}
