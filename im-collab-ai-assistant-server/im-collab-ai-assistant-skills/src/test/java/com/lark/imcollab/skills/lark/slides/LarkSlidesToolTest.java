package com.lark.imcollab.skills.lark.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LarkSlidesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createPresentationUsesSlidesCreateWithUserIdentityAndJsonSlides() throws Exception {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, """
                    {"data":{"xml_presentation_id":"slides-1","url":"https://example.feishu.cn/slides/slides-1","title":"方案汇报"}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        LarkSlidesCreateResult result = tool.createPresentation("方案汇报", List.of("<slide><data><shape type=\"text\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"50\"><content><p>标题</p></content></shape></data></slide>"));

        assertThat(result.getPresentationId()).isEqualTo("slides-1");
        assertThat(result.getPresentationUrl()).contains("slides-1");
        List<String> args = commands.get(0).arguments();
        assertThat(args).containsSequence(List.of(
                "slides", "+create",
                "--as", "user",
                "--title", "方案汇报",
                "--slides"
        ));
        int slidesArgIndex = args.indexOf("--slides") + 1;
        JsonNode slides = objectMapper.readTree(args.get(slidesArgIndex));
        assertThat(slides).hasSize(1);
        assertThat(slides.get(0).asText()).contains("<slide");
    }

    @Test
    void fetchPresentationUsesXmlPresentationGet() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            return new CliCommandResult(0, """
                    {"data":{"presentation":{"title":"方案汇报","content":"<presentation><slide/></presentation>"}}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        LarkSlidesFetchResult result = tool.fetchPresentation("slides-1");

        assertThat(result.getTitle()).isEqualTo("方案汇报");
        assertThat(result.getXml()).contains("<presentation");
        assertThat(commands.get(0).arguments()).containsSequence(List.of(
                "slides", "xml_presentations", "get",
                "--as", "user",
                "--params"
        ));
        assertThat(commands.get(0).arguments().get(commands.get(0).arguments().indexOf("--params") + 1))
                .contains("xml_presentation_id")
                .contains("slides-1");
    }

    @Test
    void createPresentationTurnsCliFailureIntoReadableException() {
        CliCommandExecutor executor = command -> new CliCommandResult(1, """
                {"error":{"message":"slides permission denied"}}
                """);
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        assertThatThrownBy(() -> tool.createPresentation("失败演示", List.of("<slide><data/></slide>")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slides permission denied");
    }
}
