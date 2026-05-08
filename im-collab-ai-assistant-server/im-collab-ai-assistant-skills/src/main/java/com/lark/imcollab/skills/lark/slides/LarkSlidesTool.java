package com.lark.imcollab.skills.lark.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class LarkSlidesTool {

    private static final Logger log = LoggerFactory.getLogger(LarkSlidesTool.class);

    private final LarkCliClient larkCliClient;
    private final LarkCliProperties properties;
    private final ObjectMapper objectMapper;

    public LarkSlidesTool(
            LarkCliClient larkCliClient,
            LarkCliProperties properties,
            ObjectMapper objectMapper
    ) {
        this.larkCliClient = larkCliClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "Scenario D: create a Lark Slides presentation from slide XML pages.")
    public LarkSlidesCreateResult createPresentation(String title, List<String> slideXmlList) {
        requireValue(title, "title");
        if (slideXmlList == null || slideXmlList.isEmpty()) {
            throw new IllegalArgumentException("slideXmlList is required");
        }

        List<String> slides = slideXmlList.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        if (slides.isEmpty()) {
            throw new IllegalArgumentException("slideXmlList is empty");
        }

        long startedAt = System.nanoTime();
        LarkSlidesCreateResult result = createEmptyPresentation(title.trim(), slides.size());
        if (result.getPresentationId() == null || result.getPresentationId().isBlank()) {
            throw new IllegalStateException("Lark Slides create response missing presentation id");
        }
        for (int index = 0; index < slides.size(); index++) {
            appendSlide(result.getPresentationId(), slides.get(index), index + 1);
        }
        log.info("Lark slides presentation created: presentationId={}, slideCount={}, elapsedMs={}",
                result.getPresentationId(), slides.size(), elapsedMs(startedAt));
        return result;
    }

    private LarkSlidesCreateResult createEmptyPresentation(String title, int slideCount) {
        List<String> args = new ArrayList<>();
        args.add("slides");
        args.add("+create");
        args.add("--as");
        args.add(resolveSlidesIdentity());
        args.add("--title");
        args.add(title);

        long startedAt = System.nanoTime();
        try {
            JsonNode root = executeJson(args);
            JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
            JsonNode presentation = data.path("presentation").isMissingNode() ? data : data.path("presentation");
            LarkSlidesCreateResult result = LarkSlidesCreateResult.builder()
                .presentationId(firstNonBlank(
                        text(data, "xml_presentation_id"),
                        text(data, "presentation_id"),
                        text(data, "presentationId"),
                        text(presentation, "xml_presentation_id"),
                        text(presentation, "presentation_id"),
                        text(presentation, "presentationId")
                ))
                .presentationUrl(firstNonBlank(
                        text(data, "url"),
                        text(data, "presentation_url"),
                        text(data, "presentationUrl"),
                        text(presentation, "url"),
                        text(presentation, "presentation_url"),
                        text(presentation, "presentationUrl")
                ))
                .title(firstNonBlank(text(data, "title"), text(presentation, "title"), title))
                .message(firstNonBlank(text(data, "message"), text(root, "message")))
                .build();
            log.info("Lark slides empty presentation created: presentationId={}, title={}, targetSlideCount={}, timeoutMs={}, elapsedMs={}",
                    result.getPresentationId(), title, slideCount, commandTimeoutMillis(), elapsedMs(startedAt));
            return result;
        } catch (RuntimeException exception) {
            log.warn("Lark slides empty presentation creation failed: title={}, targetSlideCount={}, timeoutMs={}, elapsedMs={}, error={}",
                    title, slideCount, commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw exception;
        }
    }

    private void appendSlide(String presentationId, String slideXml, int slideIndex) {
        long startedAt = System.nanoTime();
        int xmlChars = slideXml == null ? 0 : slideXml.length();
        int shapeCount = countOccurrences(slideXml, "<shape");
        try {
            createSlide(presentationId, slideXml, null);
            log.info("Lark slides page appended: presentationId={}, slideIndex={}, xmlChars={}, shapeCount={}, timeoutMs={}, elapsedMs={}",
                    presentationId, slideIndex, xmlChars, shapeCount, commandTimeoutMillis(), elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            log.warn("Lark slides page append failed: presentationId={}, slideIndex={}, xmlChars={}, shapeCount={}, timeoutMs={}, elapsedMs={}, error={}",
                    presentationId, slideIndex, xmlChars, shapeCount, commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw new IllegalStateException("Failed to append slide " + slideIndex + ": " + exception.getMessage(), exception);
        }
    }

    @Tool(description = "Scenario D: create one slide in an existing Lark Slides presentation.")
    public LarkSlidesReplaceResult createSlide(String presentationId, String slideXml, String beforeSlideId) {
        requireValue(presentationId, "presentationId");
        requireValue(slideXml, "slideXml");
        long startedAt = System.nanoTime();
        Map<String, Object> dataPayload = new java.util.LinkedHashMap<>();
        dataPayload.put("slide", Map.of("content", slideXml));
        if (beforeSlideId != null && !beforeSlideId.isBlank()) {
            dataPayload.put("before_slide_id", beforeSlideId.trim());
        }
        try (
                TempJsonFile params = writeTempJsonArg("params", Map.of("xml_presentation_id", presentationId.trim()));
                TempJsonFile data = writeTempJsonArg("data", dataPayload)
        ) {
            List<String> args = new ArrayList<>();
            args.add("slides");
            args.add("xml_presentation.slide");
            args.add("create");
            args.add("--as");
            args.add(resolveSlidesIdentity());
            args.add("--params");
            args.add(params.arg());
            args.add("--data");
            args.add(data.arg());
            args.add("--yes");
            JsonNode root = executeJson(args);
            JsonNode dataNode = root.path("data").isMissingNode() ? root : root.path("data");
            JsonNode slide = dataNode.path("slide").isMissingNode() ? dataNode : dataNode.path("slide");
            log.info("Lark slides page created: presentationId={}, beforeSlideId={}, xmlChars={}, timeoutMs={}, elapsedMs={}",
                    presentationId.trim(), beforeSlideId, slideXml.length(), commandTimeoutMillis(), elapsedMs(startedAt));
            return LarkSlidesReplaceResult.builder()
                    .presentationId(firstNonBlank(
                            text(dataNode, "xml_presentation_id"),
                            text(dataNode, "presentation_id"),
                            presentationId.trim()
                    ))
                    .slideId(firstNonBlank(
                            text(dataNode, "slide_id"),
                            text(dataNode, "id"),
                            text(slide, "slide_id"),
                            text(slide, "id")
                    ))
                    .partsCount(1)
                    .revisionId(firstNonBlank(text(dataNode, "revision_id"), text(dataNode, "revisionId")))
                    .message(firstNonBlank(text(dataNode, "message"), text(root, "message")))
                    .build();
        } catch (RuntimeException exception) {
            log.warn("Lark slides page create failed: presentationId={}, beforeSlideId={}, xmlChars={}, timeoutMs={}, elapsedMs={}, error={}",
                    presentationId.trim(), beforeSlideId, slideXml.length(), commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw exception;
        }
    }

    @Tool(description = "Scenario D: fetch a Lark Slides presentation XML by presentation id.")
    public LarkSlidesFetchResult fetchPresentation(String presentationId) {
        requireValue(presentationId, "presentationId");
        List<String> args = new ArrayList<>();
        args.add("slides");
        args.add("xml_presentations");
        args.add("get");
        args.add("--as");
        args.add(resolveSlidesIdentity());
        args.add("--params");

        JsonNode root;
        long startedAt = System.nanoTime();
        try (TempJsonFile params = writeTempJsonArg("params", Map.of("xml_presentation_id", presentationId.trim()))) {
            args.add(params.arg());
            root = executeJson(args);
            log.info("Lark slides presentation fetched: presentationId={}, timeoutMs={}, elapsedMs={}",
                    presentationId.trim(), commandTimeoutMillis(), elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            log.warn("Lark slides presentation fetch failed: presentationId={}, timeoutMs={}, elapsedMs={}, error={}",
                    presentationId.trim(), commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw exception;
        }
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode presentation = data.path("presentation").isMissingNode() ? data : data.path("presentation");
        JsonNode xmlPresentation = data.path("xml_presentation").isMissingNode() ? presentation : data.path("xml_presentation");
        return LarkSlidesFetchResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .presentationId(firstNonBlank(
                        text(xmlPresentation, "presentation_id"),
                        text(xmlPresentation, "xml_presentation_id"),
                        presentationId.trim()
                ))
                .title(firstNonBlank(text(data, "title"), text(presentation, "title"), text(xmlPresentation, "title")))
                .xml(firstNonBlank(
                        text(data, "xml"),
                        text(data, "content"),
                        text(presentation, "xml"),
                        text(presentation, "content"),
                        text(xmlPresentation, "xml"),
                        text(xmlPresentation, "content"),
                        data.toString()
                ))
                .message(firstNonBlank(text(data, "message"), text(root, "message")))
                .build();
    }

    @Tool(description = "Scenario D: fetch one Lark Slides page XML by presentation id and slide id.")
    public LarkSlidesFetchResult fetchSlide(String presentationId, String slideId) {
        requireValue(presentationId, "presentationId");
        requireValue(slideId, "slideId");
        List<String> args = new ArrayList<>();
        args.add("slides");
        args.add("xml_presentation.slide");
        args.add("get");
        args.add("--as");
        args.add(resolveSlidesIdentity());
        args.add("--params");

        JsonNode root;
        long startedAt = System.nanoTime();
        try (TempJsonFile params = writeTempJsonArg("params", Map.of(
                "xml_presentation_id", presentationId.trim(),
                "slide_id", slideId.trim()
        ))) {
            args.add(params.arg());
            root = executeJson(args);
            log.info("Lark slides page fetched: presentationId={}, slideId={}, timeoutMs={}, elapsedMs={}",
                    presentationId.trim(), slideId.trim(), commandTimeoutMillis(), elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            log.warn("Lark slides page fetch failed: presentationId={}, slideId={}, timeoutMs={}, elapsedMs={}, error={}",
                    presentationId.trim(), slideId.trim(), commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw exception;
        }
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode slide = data.path("slide").isMissingNode() ? data : data.path("slide");
        return LarkSlidesFetchResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .presentationId(firstNonBlank(text(data, "xml_presentation_id"), text(data, "presentation_id"), presentationId.trim()))
                .xml(firstNonBlank(
                        text(data, "xml"),
                        text(data, "content"),
                        text(slide, "xml"),
                        text(slide, "content"),
                        data.toString()
                ))
                .message(firstNonBlank(text(data, "message"), text(root, "message")))
                .build();
    }

    @Tool(description = "Scenario D: delete one Lark Slides page by presentation id and slide id.")
    public void deleteSlide(String presentationId, String slideId) {
        requireValue(presentationId, "presentationId");
        requireValue(slideId, "slideId");
        List<String> args = new ArrayList<>();
        args.add("slides");
        args.add("xml_presentation.slide");
        args.add("delete");
        args.add("--as");
        args.add(resolveSlidesIdentity());
        args.add("--params");
        long startedAt = System.nanoTime();
        try (TempJsonFile params = writeTempJsonArg("params", Map.of(
                "xml_presentation_id", presentationId.trim(),
                "slide_id", slideId.trim()
        ))) {
            args.add(params.arg());
            args.add("--yes");
            executeJson(args);
            log.info("Lark slides page deleted: presentationId={}, slideId={}, timeoutMs={}, elapsedMs={}",
                    presentationId.trim(), slideId.trim(), commandTimeoutMillis(), elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            log.warn("Lark slides page delete failed: presentationId={}, slideId={}, timeoutMs={}, elapsedMs={}, error={}",
                    presentationId.trim(), slideId.trim(), commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw exception;
        }
    }

    @Tool(description = "Scenario D: replace or insert blocks on an existing Lark Slides page.")
    public LarkSlidesReplaceResult replaceSlide(String presentation, String slideId, List<Map<String, Object>> parts) {
        requireValue(presentation, "presentation");
        requireValue(slideId, "slideId");
        if (parts == null || parts.isEmpty()) {
            throw new IllegalArgumentException("parts is required");
        }
        long startedAt = System.nanoTime();
        try (TempJsonFile partsFile = writeTempJsonArg("parts", parts)) {
            List<String> args = new ArrayList<>();
            args.add("slides");
            args.add("+replace-slide");
            args.add("--as");
            args.add(resolveSlidesIdentity());
            args.add("--presentation");
            args.add(presentation.trim());
            args.add("--slide-id");
            args.add(slideId.trim());
            args.add("--parts");
            args.add(partsFile.arg());
            JsonNode root = executeJson(args);
            JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
            log.info("Lark slides page replaced: presentation={}, slideId={}, parts={}, timeoutMs={}, elapsedMs={}",
                    presentation.trim(), slideId.trim(), parts.size(), commandTimeoutMillis(), elapsedMs(startedAt));
            return LarkSlidesReplaceResult.builder()
                    .presentationId(firstNonBlank(text(data, "xml_presentation_id"), text(data, "presentation_id"), presentation.trim()))
                    .slideId(firstNonBlank(text(data, "slide_id"), slideId.trim()))
                    .partsCount(data.path("parts_count").asInt(parts.size()))
                    .revisionId(firstNonBlank(text(data, "revision_id"), text(data, "revisionId")))
                    .message(firstNonBlank(text(data, "message"), text(root, "message")))
                    .build();
        } catch (RuntimeException exception) {
            log.warn("Lark slides page replace failed: presentation={}, slideId={}, parts={}, timeoutMs={}, elapsedMs={}, error={}",
                    presentation.trim(), slideId.trim(), parts.size(), commandTimeoutMillis(), elapsedMs(startedAt), exception.getMessage());
            throw exception;
        }
    }

    private JsonNode executeJson(List<String> args) {
        log.info(
                "Lark slides CLI request start: command={}, timeoutMs={}, workingDir='{}'",
                summarizeArgs(args),
                commandTimeoutMillis(),
                cliWorkingDirectory()
        );
        long startedAt = System.nanoTime();
        CliCommandResult result = larkCliClient.execute(args, null, commandTimeoutMillis());
        if (!result.isSuccess()) {
            log.warn(
                    "Lark slides CLI request failed: command={}, exitCode={}, elapsedMs={}, outputPreview={}",
                    summarizeArgs(args),
                    result.exitCode(),
                    elapsedMs(startedAt),
                    previewOutput(result.output())
            );
            throw new IllegalStateException(readableCliError(result.output()));
        }
        try {
            JsonNode parsed = larkCliClient.readJsonOutput(result.output());
            log.info(
                    "Lark slides CLI request finished: command={}, exitCode={}, elapsedMs={}, outputPreview={}",
                    summarizeArgs(args),
                    result.exitCode(),
                    elapsedMs(startedAt),
                    previewOutput(result.output())
            );
            return parsed;
        } catch (Exception exception) {
            log.warn(
                    "Lark slides CLI response parse failed: command={}, elapsedMs={}, outputPreview={}, error={}",
                    summarizeArgs(args),
                    elapsedMs(startedAt),
                    previewOutput(result.output()),
                    exception.getMessage()
            );
            throw new IllegalStateException("Failed to parse lark slides response", exception);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize slides payload", exception);
        }
    }

    private TempJsonFile writeTempJsonArg(String name, Object value) {
        try {
            Path directory = Files.createTempDirectory(cliWorkingDirectory(), ".lark-slides-");
            Path file = directory.resolve(name + ".json");
            Files.writeString(file, writeJson(value), StandardCharsets.UTF_8);
            return new TempJsonFile(file, directory, "@" + toCliRelativePath(file));
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to write temporary slides payload", exception);
        }
    }

    private Path cliWorkingDirectory() {
        String configured = properties.getWorkingDirectory();
        if (configured == null || configured.isBlank()) {
            return Path.of("").toAbsolutePath().normalize();
        }
        return Path.of(configured).toAbsolutePath().normalize();
    }

    private String toCliRelativePath(Path file) {
        Path relative = cliWorkingDirectory().relativize(file.toAbsolutePath().normalize());
        return relative.toString();
    }

    private record TempJsonFile(Path file, Path directory, String arg) implements AutoCloseable {

        @Override
        public void close() {
            try {
                Files.deleteIfExists(file);
                Files.deleteIfExists(directory);
            } catch (IOException ignored) {
                // Best-effort cleanup only.
            }
        }
    }

    private String resolveSlidesIdentity() {
        String identity = properties.getSlidesIdentity();
        return identity == null || identity.isBlank() ? "user" : identity.trim();
    }

    private long commandTimeoutMillis() {
        return Math.max(1, properties.getTimeoutSeconds()) * 1000L;
    }

    private long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
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

    private String readableCliError(String output) {
        if (output == null || output.isBlank()) {
            return "Lark Slides request failed";
        }
        try {
            JsonNode root = larkCliClient.readJsonOutput(output);
            String message = firstNonBlank(
                    root.path("error").path("message").asText(null),
                    root.path("msg").asText(null),
                    root.path("message").asText(null)
            );
            return message == null || message.isBlank() ? output : message;
        } catch (Exception ignored) {
            return output;
        }
    }

    private List<String> summarizeArgs(List<String> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        List<String> summary = new ArrayList<>(args.size());
        for (String arg : args) {
            if (arg == null) {
                summary.add(null);
                continue;
            }
            if (arg.startsWith("@")) {
                summary.add(arg);
                continue;
            }
            if (arg.length() > 120) {
                summary.add(arg.substring(0, 117) + "...");
                continue;
            }
            summary.add(arg);
        }
        return summary;
    }

    private String previewOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String compact = output.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 240) {
            return compact;
        }
        return compact.substring(0, 237) + "...";
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        String value = node.path(field).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
