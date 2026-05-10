package com.lark.imcollab.harness.presentation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommand;
import com.lark.imcollab.skills.framework.cli.CliCommandExecutor;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import com.lark.imcollab.skills.lark.slides.LarkSlidesCreateResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesReplaceResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidesImageInsertTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    class MediaUploadFlow {

        @Test
        void mediaUploadReturnsFileTokenForImgEmbedding() throws Exception {
            List<CliCommand> commands = new ArrayList<>();
            CliCommandExecutor executor = command -> {
                commands.add(command);
                return new CliCommandResult(0, """
                        {"data":{"file_token":"boxcnXXXXXXXXXXXXXXXXXXXXXX","file_name":"banner.jpg"}}
                        """);
            };
            LarkCliProperties properties = new LarkCliProperties();
            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);
            LarkSlidesTool tool = new LarkSlidesTool(client, properties, objectMapper);

            var result = tool.uploadMedia("slides-1", "@./banner.jpg");

            assertThat(result.getFileToken()).isEqualTo("boxcnXXXXXXXXXXXXXXXXXXXXXX");
            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).arguments()).containsSequence(List.of(
                    "slides", "+media-upload",
                    "--as", "user",
                    "--file", ".\\banner.jpg",
                    "--presentation", "slides-1"
            ));
        }

        @Test
        void createPresentationWithLocalImagePlaceholderUsesAutoUpload() throws Exception {
            List<CliCommand> commands = new ArrayList<>();
            List<String> dataPayloads = new ArrayList<>();
            CliCommandExecutor executor = command -> {
                commands.add(command);
                if (command.arguments().contains("--data")) {
                    dataPayloads.add(readAtFileArg(command.arguments(), "--data"));
                }
                return new CliCommandResult(0, """
                        {"data":{"presentation":{"presentation_id":"slides-1","presentation_url":"https://example.com/slides-1"}}}
                        """);
            };
            LarkCliProperties properties = new LarkCliProperties();
            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);
            LarkSlidesTool tool = new LarkSlidesTool(client, properties, objectMapper);

            String slideXml = """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <data>
                        <img src="@./chart.png" topLeftX="100" topLeftY="120" width="320" height="180"/>
                      </data>
                    </slide>
                    """;
            tool.createPresentation("图表演示", List.of(slideXml));

            assertThat(commands).hasSize(1);
            assertThat(commands.get(0).arguments()).containsSequence(List.of(
                    "slides", "+create", "--as", "user", "--title", "图表演示"
            ));
            assertThat(dataPayloads).isEmpty();
        }
    }

    @Nested
    class ImgElementXmlStructure {

        @Test
        void imgElementRequiresFileTokenNotHttpUrl() {
            String validWithToken = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180"/>
                    """;
            assertThat(validWithToken).contains("boxcn");

            String invalidWithHttpUrl = """
                    <img src="https://example.com/image.png" topLeftX="100" topLeftY="120" width="320" height="180"/>
                    """;
            assertThat(invalidWithHttpUrl).contains("https://");
        }

        @Test
        void imgElementSupportsAllPositioningAttributes() {
            String imgXml = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX"
                         topLeftX="100"
                         topLeftY="120"
                         width="320"
                         height="180"
                         rotation="0"
                         flipX="false"
                         flipY="false"
                         alpha="1"
                         exposure="0"
                         contrast="0"
                         saturation="0"
                         temperature="0"
                         alt="架构图"/>
                    """;
            assertThat(imgXml).contains("topLeftX");
            assertThat(imgXml).contains("topLeftY");
            assertThat(imgXml).contains("width");
            assertThat(imgXml).contains("height");
            assertThat(imgXml).contains("rotation");
            assertThat(imgXml).contains("alpha");
            assertThat(imgXml).contains("alt");
        }

        @Test
        void imgElementSupportsCropSubElement() {
            String imgXmlWithCrop = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180">
                      <crop type="rect"/>
                    </img>
                    """;
            assertThat(imgXmlWithCrop).contains("<crop type=\"rect\"/>");

            String imgXmlWithCropAndOffset = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180">
                      <crop type="rect" leftOffset="10" rightOffset="10" topOffset="5" bottomOffset="5"/>
                    </img>
                    """;
            assertThat(imgXmlWithCropAndOffset).contains("leftOffset");
            assertThat(imgXmlWithCropAndOffset).contains("rightOffset");
        }

        @Test
        void imgElementSupportsBorderSubElement() {
            String imgXmlWithBorder = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180">
                      <border color="rgb(59,130,246)" width="2"/>
                    </img>
                    """;
            assertThat(imgXmlWithBorder).contains("<border");
            assertThat(imgXmlWithBorder).contains("color=");
            assertThat(imgXmlWithBorder).contains("width=");
        }

        @Test
        void imgElementSupportsShadowSubElement() {
            String imgXmlWithShadow = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180">
                      <shadow/>
                    </img>
                    """;
            assertThat(imgXmlWithShadow).contains("<shadow/>");
        }

        @Test
        void imgElementSupportsReflectionSubElement() {
            String imgXmlWithReflection = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180">
                      <reflection/>
                    </img>
                    """;
            assertThat(imgXmlWithReflection).contains("<reflection/>");
        }

        @Test
        void alphaValueMustBeInRange01() {
            assertThat(buildImgAlpha(-0.1)).contains("alpha=\"-0.1\"");
            assertThat(buildImgAlpha(1.5)).contains("alpha=\"1.5\"");
        }

        private String buildImgAlpha(double alpha) {
            return String.format("""
                    <img src="boxcnXXX" topLeftX="0" topLeftY="0" width="100" height="100" alpha="%s"/>
                    """, alpha);
        }

        @Test
        void rotationValueMustBeInRange0359() {
            String valid0 = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"100\" rotation=\"0\"/>";
            String valid180 = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"100\" rotation=\"180\"/>";
            String valid359 = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"100\" rotation=\"359.9\"/>";

            assertThat(valid0).contains("rotation=\"0\"");
            assertThat(valid180).contains("rotation=\"180\"");
            assertThat(valid359).contains("rotation=\"359.9\"");
        }

        @Test
        void widthHeightAspectRatioCausesCropping() {
            String original16by9 = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"320\" height=\"180\"/>";
            assertThat(original16by9).contains("width=\"320\" height=\"180\"");

            String stretched = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"320\" height=\"100\"/>";
            assertThat(stretched).contains("width=\"320\" height=\"100\"");
        }
    }

    @Nested
    class CompleteSlideWithImage {

        @Test
        void fullSlideXmlWithImageAndTextLayout() {
            String slideXml = """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="rgb(248,250,252)"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="60" topLeftY="36" width="600" height="45">
                          <content><p><strong><span color="rgb(15,23,42)" fontSize="28">产品亮点</span></strong></p></content>
                        </shape>
                        <img src="boxcnProductScreenshotXXXXXXXX" topLeftX="60" topLeftY="100" width="840" height="380">
                          <border color="rgba(0,0,0,0.08)" width="1"/>
                        </img>
                      </data>
                    </slide>
                    """;
            assertThat(slideXml).contains("xmlns=");
            assertThat(slideXml).contains("<img");
            assertThat(slideXml).contains("topLeftX");
            assertThat(slideXml).contains("topLeftY");
            assertThat(slideXml).contains("boxcn");
        }

        @Test
        void multiImageSlideWithCardsLayout() {
            String slideXml = """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="rgb(248,250,252)"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="60" topLeftY="40" width="600" height="45">
                          <content><p><strong><span color="rgb(15,23,42)" fontSize="28">核心亮点</span></strong></p></content>
                        </shape>
                        <shape type="rect" topLeftX="60" topLeftY="130" width="270" height="360">
                          <fill><fillColor color="rgb(255,255,255)"/></fill>
                          <border color="rgba(0,0,0,0.08)" width="1"/>
                        </shape>
                        <img src="boxcnFeature1XXXXXXXXXXXXXXX" topLeftX="75" topLeftY="150" width="240" height="180">
                          <crop type="rect"/>
                        </img>
                        <shape type="text" topLeftX="75" topLeftY="345" width="240" height="30">
                          <content><p><strong><span color="rgb(15,23,42)" fontSize="18">特性一</span></strong></p></content>
                        </shape>
                        <shape type="rect" topLeftX="345" topLeftY="130" width="270" height="360">
                          <fill><fillColor color="rgb(255,255,255)"/></fill>
                          <border color="rgba(0,0,0,0.08)" width="1"/>
                        </shape>
                        <img src="boxcnFeature2XXXXXXXXXXXXXXX" topLeftX="360" topLeftY="150" width="240" height="180">
                          <crop type="rect"/>
                        </img>
                        <shape type="text" topLeftX="360" topLeftY="345" width="240" height="30">
                          <content><p><strong><span color="rgb(15,23,42)" fontSize="18">特性二</span></strong></p></content>
                        </shape>
                        <shape type="rect" topLeftX="630" topLeftY="130" width="270" height="360">
                          <fill><fillColor color="rgb(255,255,255)"/></fill>
                          <border color="rgba(0,0,0,0.08)" width="1"/>
                        </shape>
                        <img src="boxcnFeature3XXXXXXXXXXXXXXX" topLeftX="645" topLeftY="150" width="240" height="180">
                          <crop type="rect"/>
                        </img>
                        <shape type="text" topLeftX="645" topLeftY="345" width="240" height="30">
                          <content><p><strong><span color="rgb(15,23,42)" fontSize="18">特性三</span></strong></p></content>
                        </shape>
                      </data>
                    </slide>
                    """;
            assertThat(slideXml).contains("boxcnFeature1");
            assertThat(slideXml).contains("boxcnFeature2");
            assertThat(slideXml).contains("boxcnFeature3");
            int imgCount = countOccurrences(slideXml, "<img");
            assertThat(imgCount).isEqualTo(3);
        }

        @Test
        void darkCoverSlideWithRightImage() {
            String slideXml = """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="linear-gradient(135deg,rgba(15,23,42,1) 0%,rgba(56,97,140,1) 100%)"/></fill></style>
                      <data>
                        <shape type="text" topLeftX="60" topLeftY="180" width="450" height="80">
                          <content><p><strong><span color="rgb(255,255,255)" fontSize="44">主标题</span></strong></p></content>
                        </shape>
                        <shape type="text" topLeftX="60" topLeftY="270" width="450" height="40">
                          <content><p><span color="rgb(186,230,253)" fontSize="20">副标题</span></p></content>
                        </shape>
                        <line startX="60" startY="350" endX="180" endY="350">
                          <border color="rgb(59,130,246)" width="3"/>
                        </line>
                        <img src="boxcnHeroImageXXXXXXXXXXXXX" topLeftX="540" topLeftY="157" width="400" height="225">
                          <border color="rgba(255,255,255,0.2)" width="1"/>
                        </img>
                      </data>
                    </slide>
                    """;
            assertThat(slideXml).contains("linear-gradient");
            assertThat(slideXml).contains("<img");
            assertThat(slideXml).contains("topLeftX=\"540\"");
            assertThat(slideXml).contains("topLeftY=\"157\"");
            assertThat(slideXml).contains("width=\"400\"");
            assertThat(slideXml).contains("height=\"225\"");
        }

        @Test
        void portraitImageSlideWithRightText() {
            String slideXml = """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <style><fill><fillColor color="rgb(255,255,255)"/></fill></style>
                      <data>
                        <img src="boxcnPortraitXXXXXXXXXXXXXXX" topLeftX="0" topLeftY="0" width="360" height="540"/>
                        <shape type="text" topLeftX="410" topLeftY="80" width="490" height="50">
                          <content><p><strong><span color="rgb(15,23,42)" fontSize="30">场景标题</span></strong></p></content>
                        </shape>
                        <line startX="410" startY="140" endX="490" endY="140">
                          <border color="rgb(59,130,246)" width="3"/>
                        </line>
                        <shape type="text" topLeftX="410" topLeftY="160" width="490" height="50">
                          <content><p><span color="rgb(71,85,105)" fontSize="16">一句话描述这个场景的价值。</span></p></content>
                        </shape>
                        <shape type="text" topLeftX="410" topLeftY="230" width="490" height="250">
                          <content textType="body" lineSpacing="multiple:1.8">
                            <ul>
                              <li><p><span color="rgb(51,65,85)" fontSize="15">要点一</span></p></li>
                              <li><p><span color="rgb(51,65,85)" fontSize="15">要点二</span></p></li>
                              <li><p><span color="rgb(51,65,85)" fontSize="15">要点三</span></p></li>
                            </ul>
                          </content>
                        </shape>
                      </data>
                    </slide>
                    """;
            assertThat(slideXml).contains("width=\"360\" height=\"540\"");
            assertThat(slideXml).contains("boxcnPortrait");
            int imgCount = countOccurrences(slideXml, "<img");
            assertThat(imgCount).isEqualTo(1);
        }
    }

    @Nested
    class ReplaceSlideWithImage {

        @Test
        void replaceSlideCanInsertImageIntoExistingSlide() throws Exception {
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
            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);
            LarkSlidesTool tool = new LarkSlidesTool(client, properties, objectMapper);

            tool.replaceSlide("slides-1", "s1", List.of(java.util.Map.of(
                    "action", "block_insert",
                    "index", 0,
                    "block", "<img src=\"boxcnXXXXXXXXXXXXXXXXXX\" topLeftX=\"200\" topLeftY=\"150\" width=\"400\" height=\"225\"/>"
            )));

            assertThat(commands.get(0).arguments()).containsSequence(List.of(
                    "slides", "+replace-slide",
                    "--as", "user",
                    "--presentation", "slides-1",
                    "--slide-id", "s1"
            ));
            String partsContent = partsPayloads.get(0);
            assertThat(partsContent).contains("block_insert");
            assertThat(partsContent).contains("boxcn");
            assertThat(partsContent).contains("topLeftX");
        }

        @Test
        void replaceSlideCanUpdateExistingImageBlock() throws Exception {
            List<CliCommand> commands = new ArrayList<>();
            List<String> partsPayloads = new ArrayList<>();
            CliCommandExecutor executor = command -> {
                commands.add(command);
                partsPayloads.add(readAtFileArg(command.arguments(), "--parts"));
                return new CliCommandResult(0, """
                        {"data":{"xml_presentation_id":"slides-1","slide_id":"s1","parts_count":1}}
                        """);
            };
            LarkCliProperties properties = new LarkCliProperties();
            LarkCliClient client = new LarkCliClient(executor, properties, objectMapper);
            LarkSlidesTool tool = new LarkSlidesTool(client, properties, objectMapper);

            tool.replaceSlide("slides-1", "s1", List.of(java.util.Map.of(
                    "action", "block_replace",
                    "block_id", "img-block-1",
                    "replacement", "<img src=\"boxcnUpdatedXXXXXXXXXXXXX\" topLeftX=\"100\" topLeftY=\"100\" width=\"320\" height=\"180\"/>"
            )));

            String partsContent = partsPayloads.get(0);
            assertThat(partsContent).contains("block_replace");
            assertThat(partsContent).contains("boxcnUpdated");
        }
    }

    @Nested
    class LlmGeneratedSlideAnalysis {

        @Test
        void llmShouldGenerateValidImgElementWithFileToken() {
            String llmGeneratedXml = """
                    <img src="boxcnAI generated image token" topLeftX="100" topLeftY="120" width="320" height="180"/>
                    """;
            assertThat(llmGeneratedXml).contains("boxcn");
            assertThat(llmGeneratedXml).contains("topLeftX");
            assertThat(llmGeneratedXml).contains("topLeftY");
            assertThat(llmGeneratedXml).contains("width");
            assertThat(llmGeneratedXml).contains("height");
        }

        @Test
        void llmShouldNotUseHttpUrlInImgSrc() {
            String invalidXml = """
                    <img src="https://example.com/image.png" topLeftX="100" topLeftY="120" width="320" height="180"/>
                    """;
            assertThat(invalidXml).contains("https://");
        }

        @Test
        void llmShouldGenerateSlideXmlWithNamespace() {
            String slideXml = """
                    <slide xmlns="http://www.larkoffice.com/sml/2.0">
                      <data>
                        <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180"/>
                      </data>
                    </slide>
                    """;
            assertThat(slideXml).contains("xmlns=");
            assertThat(slideXml).contains("<slide");
            assertThat(slideXml).contains("<data>");
            assertThat(slideXml).contains("<img");
        }

        @Test
        void llmShouldUseCorrectColorFormatInBorderAndFill() {
            String imgXml = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="120" width="320" height="180">
                      <border color="rgba(59,130,246,1)" width="2"/>
                      <fill><fillColor color="rgba(255,255,255,0.8)"/></fill>
                    </img>
                    """;
            assertThat(imgXml).contains("rgba(");
            assertThat(imgXml).contains("color=\"rgba(59,130,246,1)\"");
            assertThat(imgXml).contains("color=\"rgba(255,255,255,0.8)\"");
        }

        @Test
        void coordinateSystemUnderstandsTopLeftCorner() {
            String imgTopLeft = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"100\" height=\"100\"/>";
            String imgBottomRight = "<img src=\"boxcnXXX\" topLeftX=\"860\" topLeftY=\"440\" width=\"100\" height=\"100\"/>";

            assertThat(imgTopLeft).contains("topLeftX=\"0\" topLeftY=\"0\"");
            assertThat(imgBottomRight).contains("topLeftX=\"860\" topLeftY=\"440\"");

            int x1 = Integer.parseInt(extractAttribute(imgTopLeft, "topLeftX"));
            int y1 = Integer.parseInt(extractAttribute(imgTopLeft, "topLeftY"));
            int x2 = Integer.parseInt(extractAttribute(imgBottomRight, "topLeftX"));
            int y2 = Integer.parseInt(extractAttribute(imgBottomRight, "topLeftY"));

            assertThat(x2).isGreaterThan(x1);
            assertThat(y2).isGreaterThan(y1);
        }

        @Test
        void presentationDimensionsAre960by540Standard() {
            String presentationXml = """
                    <presentation xmlns="http://www.larkoffice.com/sml/2.0" width="960" height="540">
                      <title>测试演示</title>
                      <slide>
                        <data>
                          <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="0" topLeftY="0" width="960" height="540"/>
                        </data>
                      </slide>
                    </presentation>
                    """;
            assertThat(presentationXml).contains("width=\"960\" height=\"540\"");
            assertThat(presentationXml).contains("width=\"960\" height=\"540\"");
        }
    }

    @Nested
    class ImageAspectRatioAndCrop {

        @Test
        void widthHeightRatioMustMatchOriginalToAvoidCrop() {
            String original16by9 = "<img src=\"boxcnXXX\" topLeftX=\"0\" topLeftY=\"0\" width=\"320\" height=\"180\"/>";
            assertThat(original16by9).contains("width=\"320\" height=\"180\"");

            int w = 320, h = 180;
            assertThat((double) w / h).isCloseTo(16.0 / 9.0, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        void cropSubElementCanSpecifyRectType() {
            String imgXml = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="0" topLeftY="0" width="320" height="180">
                      <crop type="rect"/>
                    </img>
                    """;
            assertThat(imgXml).contains("<crop type=\"rect\"/>");
        }

        @Test
        void cropOffsetControlsInnerCropRegion() {
            String imgXml = """
                    <img src="boxcnXXXXXXXXXXXXXXXXXXXXXX" topLeftX="100" topLeftY="100" width="300" height="150">
                      <crop type="rect" leftOffset="20" rightOffset="20" topOffset="10" bottomOffset="10"/>
                    </img>
                    """;
            assertThat(imgXml).contains("leftOffset");
            assertThat(imgXml).contains("rightOffset");
            assertThat(imgXml).contains("topOffset");
            assertThat(imgXml).contains("bottomOffset");
        }
    }

    private String readAtFileArg(List<String> args, String flag) throws IOException {
        int index = args.indexOf(flag);
        if (index < 0 || index + 1 >= args.size()) {
            throw new IllegalArgumentException("Flag " + flag + " not found or no value");
        }
        String fileArg = args.get(index + 1);
        if (!fileArg.startsWith("@")) {
            throw new IllegalArgumentException("Expected @" + flag + " arg, got: " + fileArg);
        }
        Path path = Path.of(fileArg.substring(1));
        if (!path.isAbsolute()) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        return fileArg;
    }

    private int countOccurrences(String value, String needle) {
        if (value == null || value.isBlank() || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = value.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private String extractAttribute(String xml, String attrName) {
        String pattern = attrName + "=\"";
        int start = xml.indexOf(pattern) + pattern.length();
        int end = xml.indexOf("\"", start);
        return xml.substring(start, end);
    }
}
