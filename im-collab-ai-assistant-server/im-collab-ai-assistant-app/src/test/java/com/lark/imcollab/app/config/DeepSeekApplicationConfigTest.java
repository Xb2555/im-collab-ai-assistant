package com.lark.imcollab.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeepSeekApplicationConfigTest {

    @Test
    void defaultDeepSeekModelUsesNonThinkingChatModel() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application", new ClassPathResource("application.yml"));

        Object model = sources.get(0).getProperty("spring.ai.deepseek.chat.options.model");

        assertThat(model).isEqualTo("${DEEPSEEK_MODEL:deepseek-chat}");
    }
}
