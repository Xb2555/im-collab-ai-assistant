package com.lark.imcollab.skills.lark.slides;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.framework.cli.CliCommandResult;
import com.lark.imcollab.skills.lark.cli.LarkCliClient;
import com.lark.imcollab.skills.lark.config.LarkCliProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class LarkSlidesTool {

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

        List<String> args = new ArrayList<>();
        args.add("slides");
        args.add("+create");
        args.add("--as");
        args.add(resolveSlidesIdentity());
        args.add("--title");
        args.add(title.trim());
        args.add("--slides");
        args.add(writeJson(slides));

        JsonNode root = executeJson(args);
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode presentation = data.path("presentation").isMissingNode() ? data : data.path("presentation");
        return LarkSlidesCreateResult.builder()
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
                .title(firstNonBlank(text(data, "title"), text(presentation, "title"), title.trim()))
                .message(firstNonBlank(text(data, "message"), text(root, "message")))
                .build();
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
        args.add(writeJson(java.util.Map.of("xml_presentation_id", presentationId.trim())));

        JsonNode root = executeJson(args);
        JsonNode data = root.path("data").isMissingNode() ? root : root.path("data");
        JsonNode presentation = data.path("presentation").isMissingNode() ? data : data.path("presentation");
        return LarkSlidesFetchResult.builder()
                .success(root.path("success").asBoolean(data.path("success").asBoolean(true)))
                .presentationId(presentationId.trim())
                .title(firstNonBlank(text(data, "title"), text(presentation, "title")))
                .xml(firstNonBlank(
                        text(data, "xml"),
                        text(data, "content"),
                        text(presentation, "xml"),
                        text(presentation, "content"),
                        data.toString()
                ))
                .message(firstNonBlank(text(data, "message"), text(root, "message")))
                .build();
    }

    private JsonNode executeJson(List<String> args) {
        CliCommandResult result = larkCliClient.execute(args, null, commandTimeoutMillis());
        if (!result.isSuccess()) {
            throw new IllegalStateException(readableCliError(result.output()));
        }
        try {
            return larkCliClient.readJsonOutput(result.output());
        } catch (Exception exception) {
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

    private String resolveSlidesIdentity() {
        String identity = properties.getSlidesIdentity();
        return identity == null || identity.isBlank() ? "user" : identity.trim();
    }

    private long commandTimeoutMillis() {
        return Math.max(1, properties.getTimeoutSeconds()) * 1000L;
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
