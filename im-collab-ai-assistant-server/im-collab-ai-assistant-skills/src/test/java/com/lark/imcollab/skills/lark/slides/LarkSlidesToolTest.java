package com.lark.imcollab.skills.lark.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LarkSlidesToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void createPresentationCreatesEmptyDeckThenAppendsSlidesWithFileArgs() throws Exception {
        List<CliCommand> commands = new ArrayList<>();
        List<String> paramsPayloads = new ArrayList<>();
        List<String> dataPayloads = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            List<String> args = command.arguments();
            if (args.contains("xml_presentation.slide")) {
                paramsPayloads.add(readAtFileArg(args, "--params"));
                dataPayloads.add(readAtFileArg(args, "--data"));
                return new CliCommandResult(0, """
                        {"data":{"slide":{"id":"slide-1"}}}
                        """);
            }
            return new CliCommandResult(0, """
                    {"data":{"xml_presentation_id":"slides-1","url":"https://example.feishu.cn/slides/slides-1","title":"方案汇报"}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        LarkSlidesCreateResult result = tool.createPresentation("方案汇报", List.of(
                "<slide><data><shape type=\"text\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"50\"><content><p>标题一</p></content></shape></data></slide>",
                "<slide><data><shape type=\"text\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"50\"><content><p>标题二</p></content></shape></data></slide>"
        ));

        assertThat(result.getPresentationId()).isEqualTo("slides-1");
        assertThat(result.getPresentationUrl()).contains("slides-1");
        List<String> args = commands.get(0).arguments();
        assertThat(args).containsSequence(List.of(
                "slides", "+create",
                "--as", "user",
                "--title", "方案汇报"
        ));
        assertThat(args).doesNotContain("--slides");
        assertThat(commands).hasSize(3);
        assertThat(commands.get(1).arguments()).containsSequence(List.of(
                "slides", "xml_presentation.slide", "create",
                "--as", "user",
                "--params"
        ));
        assertThat(commands.get(1).arguments()).contains("--data", "--yes");
        assertThat(commands.get(1).arguments().get(commands.get(1).arguments().indexOf("--params") + 1)).startsWith("@");
        assertThat(commands.get(1).arguments().get(commands.get(1).arguments().indexOf("--data") + 1)).startsWith("@");
        assertThat(paramsPayloads).hasSize(2);
        assertThat(dataPayloads).hasSize(2);
        assertThat(objectMapper.readTree(paramsPayloads.get(0)).path("xml_presentation_id").asText()).isEqualTo("slides-1");
        assertThat(objectMapper.readTree(dataPayloads.get(0)).path("slide").path("content").asText()).contains("标题一");
        assertThat(objectMapper.readTree(dataPayloads.get(1)).path("slide").path("content").asText()).contains("标题二");
    }

    @Test
    void fetchPresentationUsesXmlPresentationGet() throws Exception {
        List<CliCommand> commands = new ArrayList<>();
        List<String> paramsPayloads = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            paramsPayloads.add(readAtFileArg(command.arguments(), "--params"));
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
        assertThat(commands.get(0).arguments().get(commands.get(0).arguments().indexOf("--params") + 1)).startsWith("@");
        assertThat(objectMapper.readTree(paramsPayloads.get(0)).path("xml_presentation_id").asText()).isEqualTo("slides-1");
    }

    @Test
    void fetchPresentationSupportsRealXmlPresentationWrapper() {
        CliCommandExecutor executor = command -> new CliCommandResult(0, """
                {"data":{"xml_presentation":{"presentation_id":"slides-1","content":"<presentation><slide id=\\"s1\\"/></presentation>","revision_id":1}}}
                """);
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        LarkSlidesFetchResult result = tool.fetchPresentation("slides-1");

        assertThat(result.getPresentationId()).isEqualTo("slides-1");
        assertThat(result.getXml()).contains("<presentation>");
    }

    @Test
    void replaceSlideUsesShortcutWithPartsFile() throws Exception {
        List<CliCommand> commands = new ArrayList<>();
        List<String> partsPayloads = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            partsPayloads.add(readAtFileArg(command.arguments(), "--parts"));
            return new CliCommandResult(0, """
                    {"data":{"xml_presentation_id":"slides-1","slide_id":"s1","parts_count":1,"revision_id":"12"}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        LarkSlidesReplaceResult result = tool.replaceSlide("slides-1", "s1", List.of(java.util.Map.of(
                "action", "block_replace",
                "block_id", "b1",
                "replacement", "<shape type=\"text\"><content><p>新标题</p></content></shape>"
        )));

        assertThat(result.getSlideId()).isEqualTo("s1");
        assertThat(commands.get(0).arguments()).containsSequence(List.of(
                "slides", "+replace-slide",
                "--as", "user",
                "--presentation", "slides-1",
                "--slide-id", "s1",
                "--parts"
        ));
        assertThat(commands.get(0).arguments().get(commands.get(0).arguments().indexOf("--parts") + 1)).startsWith("@");
        assertThat(objectMapper.readTree(partsPayloads.get(0)).get(0).path("block_id").asText()).isEqualTo("b1");
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

    @Test
    void createPresentationReportsSlideIndexWhenAppendFails() {
        List<CliCommand> commands = new ArrayList<>();
        CliCommandExecutor executor = command -> {
            commands.add(command);
            if (command.arguments().contains("xml_presentation.slide") && commands.size() == 3) {
                return new CliCommandResult(1, """
                        {"error":{"message":"append failed on server"}}
                        """);
            }
            if (command.arguments().contains("xml_presentation.slide")) {
                return new CliCommandResult(0, "{}");
            }
            return new CliCommandResult(0, """
                    {"data":{"xml_presentation_id":"slides-1","url":"https://example.feishu.cn/slides/slides-1","title":"方案汇报"}}
                    """);
        };
        LarkCliProperties properties = new LarkCliProperties();
        LarkSlidesTool tool = new LarkSlidesTool(new LarkCliClient(executor, properties, objectMapper), properties, objectMapper);

        assertThatThrownBy(() -> tool.createPresentation("失败演示", List.of("<slide><data>1</data></slide>", "<slide><data>2</data></slide>")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to append slide 2")
                .hasMessageContaining("append failed on server");
    }

    private String readAtFileArg(List<String> args, String flag) throws IOException {
        int index = args.indexOf(flag);
        assertThat(index).isGreaterThanOrEqualTo(0);
        assertThat(index + 1).isLessThan(args.size());
        String fileArg = args.get(index + 1);
        assertThat(fileArg).startsWith("@");
        Path path = Path.of(fileArg.substring(1));
        assertThat(path.isAbsolute()).isFalse();
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
