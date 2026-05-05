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
    private static final Pattern BLOCK_WITH_ID_PATTERN = Pattern.compile(
            "<([a-zA-Z0-9]+)\\b[^>]*id=\"([^\"]+)\"[^>]*>(.*?)</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern QUOTED_TEXT_PATTERN = Pattern.compile("[\"“「『]([^\"”」』]{2,})[\"”」』]");
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern FRAGMENT_WRAPPER_PATTERN = Pattern.compile(
            "^\\s*<fragment\\b[^>]*>\\s*(.*?)\\s*</fragment>\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
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

    public HeadingBlock findFirstTopLevelHeading(List<HeadingBlock> headings) {
        if (headings == null || headings.isEmpty()) {
            return null;
        }
        int minLevel = headings.stream().mapToInt(HeadingBlock::getLevel).min().orElse(Integer.MAX_VALUE);
        for (HeadingBlock heading : headings) {
            if (heading.getLevel() == minLevel) {
                return heading;
            }
        }
        return headings.get(0);
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

    public List<BlockNode> parseBlockNodes(String xml) {
        if (!hasText(xml)) {
            return List.of();
        }
        List<BlockNode> blocks = new ArrayList<>();
        Matcher matcher = BLOCK_WITH_ID_PATTERN.matcher(xml);
        while (matcher.find()) {
            String tagName = matcher.group(1);
            String blockId = matcher.group(2);
            String plainText = stripTags(matcher.group(3));
            if (hasText(blockId)) {
                blocks.add(new BlockNode(blockId.trim(), tagName == null ? "block" : tagName.trim().toLowerCase(), plainText));
            }
        }
        return blocks;
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

    public String unwrapMarkdownFragment(String markdown) {
        if (!hasText(markdown)) {
            return "";
        }
        Matcher matcher = FRAGMENT_WRAPPER_PATTERN.matcher(markdown);
        if (!matcher.matches()) {
            return markdown.trim();
        }
        return matcher.group(1).trim();
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

    @Getter
    @AllArgsConstructor
    public static class BlockNode {
        private final String blockId;
        private final String tagName;
        private final String plainText;
    }
}
