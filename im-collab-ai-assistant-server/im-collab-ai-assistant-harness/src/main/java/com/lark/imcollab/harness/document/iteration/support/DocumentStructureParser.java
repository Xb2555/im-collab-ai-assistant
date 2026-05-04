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
    private static final Pattern HEADING_INDEX_PATTERN = Pattern.compile("^[一二三四五六七八九十0-9]+[、.．]\\s*");
    private static final Pattern ORDINAL_SECTION_PATTERN = Pattern.compile(
            "第\\s*([一二三四五六七八九十百千万两0-9]+)\\s*(小节|章节|章|节|部分)"
    );
    private static final Pattern DECIMAL_HEADING_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)+)");

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

    public HeadingBlock resolveOrdinalHeading(String instruction, List<HeadingBlock> headings) {
        if (!hasText(instruction) || headings == null || headings.isEmpty()) {
            return null;
        }
        Matcher matcher = ORDINAL_SECTION_PATTERN.matcher(instruction);
        if (!matcher.find()) {
            return null;
        }
        int ordinal = parseChineseOrdinal(matcher.group(1));
        if (ordinal <= 0) {
            return null;
        }
        String unit = matcher.group(2);
        List<HeadingBlock> candidates = selectOrdinalCandidates(headings, unit);
        if ("小节".equals(unit) && hasDecimalNumberedSubsections(candidates)) {
            return resolveOrdinalByHeadingNumber(candidates, ordinal, unit);
        }
        HeadingBlock semanticMatch = resolveOrdinalByHeadingNumber(candidates, ordinal, unit);
        if (semanticMatch != null) {
            return semanticMatch;
        }
        if (ordinal > candidates.size()) {
            return null;
        }
        return candidates.get(ordinal - 1);
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
                .replace(")", "");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<HeadingBlock> selectOrdinalCandidates(List<HeadingBlock> headings, String unit) {
        if ("小节".equals(unit)) {
            int minLevel = headings.stream().mapToInt(HeadingBlock::getLevel).min().orElse(Integer.MAX_VALUE);
            List<HeadingBlock> nested = headings.stream().filter(heading -> heading.getLevel() > minLevel).toList();
            return nested.isEmpty() ? headings : nested;
        }
        int minLevel = headings.stream().mapToInt(HeadingBlock::getLevel).min().orElse(Integer.MAX_VALUE);
        List<HeadingBlock> topLevel = headings.stream().filter(heading -> heading.getLevel() == minLevel).toList();
        return topLevel.isEmpty() ? headings : topLevel;
    }

    private int parseChineseOrdinal(String raw) {
        if (!hasText(raw)) {
            return -1;
        }
        String normalized = raw.trim();
        if (normalized.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(normalized);
            } catch (NumberFormatException exception) {
                return -1;
            }
        }
        normalized = normalized.replace("两", "二");
        int result = 0;
        int section = 0;
        int number = 0;
        for (char ch : normalized.toCharArray()) {
            int digit = switch (ch) {
                case '零' -> 0;
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                default -> -1;
            };
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = switch (ch) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                case '万' -> 10000;
                default -> -1;
            };
            if (unit < 0) {
                return -1;
            }
            if (unit == 10000) {
                section = (section + Math.max(number, 1)) * unit;
                result += section;
                section = 0;
            } else {
                section += Math.max(number, 1) * unit;
            }
            number = 0;
        }
        return result + section + number;
    }

    private HeadingBlock resolveOrdinalByHeadingNumber(List<HeadingBlock> candidates, int ordinal, String unit) {
        if (!"小节".equals(unit) || candidates == null || candidates.isEmpty()) {
            return null;
        }
        List<HeadingBlock> decimalCandidates = candidates.stream()
                .filter(candidate -> extractTrailingDecimalOrdinal(candidate.getText()) > 0)
                .toList();
        if (decimalCandidates.isEmpty()) {
            return null;
        }
        for (HeadingBlock candidate : decimalCandidates) {
            if (extractTrailingDecimalOrdinal(candidate.getText()) == ordinal) {
                return candidate;
            }
        }
        return null;
    }

    private boolean hasDecimalNumberedSubsections(List<HeadingBlock> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        return candidates.stream().anyMatch(candidate -> extractTrailingDecimalOrdinal(candidate.getText()) > 0);
    }

    private int extractTrailingDecimalOrdinal(String headingText) {
        if (!hasText(headingText)) {
            return -1;
        }
        Matcher matcher = DECIMAL_HEADING_PATTERN.matcher(headingText.trim());
        if (!matcher.find()) {
            return -1;
        }
        String[] parts = matcher.group(1).split("\\.");
        if (parts.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (NumberFormatException exception) {
            return -1;
        }
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
