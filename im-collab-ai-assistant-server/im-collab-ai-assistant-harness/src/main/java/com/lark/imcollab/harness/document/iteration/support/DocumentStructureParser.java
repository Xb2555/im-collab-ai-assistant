package com.lark.imcollab.harness.document.iteration.support;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentStructureParser {

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "<h([1-6])\\b[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</h[1-6]>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BLOCK_ID_PATTERN = Pattern.compile("id=\"([^\"]+)\"");
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[\"“「『]([^\"”」』]{2,})[\"”」』]");
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern HEADING_INDEX_PATTERN = Pattern.compile("^[一二三四五六七八九十0-9]+[、.．]\\s*");

    public List<HeadingBlock> parseHeadings(String xml) {
        if (!hasText(xml)) {
            return List.of();
        }
        List<HeadingBlock> headings = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(xml);
        while (matcher.find()) {
            int level = Integer.parseInt(matcher.group(1));
            String blockId = matcher.group(2);
            String text = stripTags(matcher.group(3));
            if (hasText(blockId) && hasText(text)) {
                headings.add(new HeadingBlock(blockId.trim(), text.trim(), level));
            }
        }
        return headings;
    }

    public List<String> parseBlockIds(String xml) {
        if (!hasText(xml)) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = BLOCK_ID_PATTERN.matcher(xml);
        while (matcher.find()) {
            String id = matcher.group(1);
            if (hasText(id)) {
                ids.add(id.trim());
            }
        }
        return List.copyOf(ids);
    }

    public String extractQuotedText(String instruction) {
        if (!hasText(instruction)) {
            return null;
        }
        Matcher matcher = QUOTED_TEXT_PATTERN.matcher(instruction);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    public int countOccurrences(String text, String snippet) {
        if (!hasText(text) || !hasText(snippet)) {
            return 0;
        }
        int count = 0;
        int fromIndex = 0;
        while (true) {
            int index = text.indexOf(snippet, fromIndex);
            if (index < 0) {
                return count;
            }
            count++;
            fromIndex = index + snippet.length();
        }
    }

    public List<HeadingBlock> matchHeadings(String instruction, List<HeadingBlock> headings) {
        if (!hasText(instruction) || headings == null || headings.isEmpty()) {
            return List.of();
        }
        String normalizedInstruction = normalizeText(instruction);
        List<HeadingBlock> matches = new ArrayList<>();
        for (HeadingBlock heading : headings) {
            String normalizedHeading = normalizeHeadingText(heading.getText());
            if (normalizedHeading.isEmpty()) {
                continue;
            }
            if (normalizedInstruction.contains(normalizedHeading) || normalizedHeading.contains(normalizedInstruction)) {
                matches.add(heading);
            }
        }
        return preferLongest(matches);
    }

    public String stripTags(String value) {
        if (!hasText(value)) {
            return "";
        }
        return TAG_PATTERN.matcher(value).replaceAll("")
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")
                .trim();
    }

    private List<HeadingBlock> preferLongest(List<HeadingBlock> matches) {
        if (matches.size() <= 1) {
            return matches;
        }
        int maxLength = matches.stream().map(HeadingBlock::getText).mapToInt(String::length).max().orElse(0);
        List<HeadingBlock> preferred = matches.stream()
                .filter(item -> item.getText() != null && item.getText().length() == maxLength)
                .toList();
        return preferred.isEmpty() ? matches : preferred;
    }

    private String normalizeHeadingText(String value) {
        return normalizeText(HEADING_INDEX_PATTERN.matcher(stripTags(value)).replaceFirst(""));
    }

    private String normalizeText(String value) {
        if (!hasText(value)) {
            return "";
        }
        return value.replaceAll("\\s+", "")
                .replace("：", "")
                .replace(":", "")
                .replace("（", "")
                .replace("）", "")
                .replace("(", "")
                .replace(")", "")
                .replace("这部分", "")
                .replace("这一部分", "")
                .replace("这一节", "")
                .replace("这节", "")
                .replace("章节", "")
                .replace("段落", "")
                .replace("部分", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Getter
    @AllArgsConstructor
    public static class HeadingBlock {
        private final String blockId;
        private final String text;
        private final int level;
    }
}
