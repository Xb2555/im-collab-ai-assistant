package com.lark.imcollab.planner.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PromptTemplateService {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.-]+)\\s*}}");
    private static final String CLASSPATH_PREFIX = "classpath:";

    private final ResourceLoader resourceLoader;
    private final Map<String, String> templateCache = new ConcurrentHashMap<>();

    public PromptTemplateService(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    public String render(String templatePath, Map<String, String> variables) {
        String template = loadTemplate(templatePath);
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(template);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = variables.getOrDefault(key, "");
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    public boolean exists(String templatePath) {
        Resource resource = resourceLoader.getResource(CLASSPATH_PREFIX + templatePath);
        return resource.exists();
    }

    private String loadTemplate(String templatePath) {
        return templateCache.computeIfAbsent(templatePath, this::readTemplate);
    }

    private String readTemplate(String templatePath) {
        Resource resource = resourceLoader.getResource(CLASSPATH_PREFIX + templatePath);
        if (!resource.exists()) {
            throw new IllegalArgumentException("Prompt template not found: " + templatePath);
        }
        try {
            byte[] bytes = resource.getInputStream().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt template: " + templatePath, e);
        }
    }
}
