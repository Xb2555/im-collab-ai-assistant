package com.lark.imcollab.harness.document.template;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DocumentBodyNormalizer {

    private static final Pattern LEADING_HEADING_MARKS = Pattern.compile("^#+\\s*");
    private static final Pattern LEADING_SECTION_ORDINAL = Pattern.compile(
            "^(?:第[一二三四五六七八九十百千万0-9]+[章节部分篇]\\s*|\\(?[一二三四五六七八九十百千万0-9]+\\)?[、.．]\\s*|[（(][一二三四五六七八九十百千万0-9]+[）)]\\s*|[0-9]+(?:\\.[0-9]+)*\\s+)"
    );
    private static final Pattern PLAIN_DECIMAL_SECTION = Pattern.compile("^([0-9]+(?:\\.[0-9]+)+)\\s+(.+)$");
    private static final Pattern PLAIN_CHINESE_SECTION = Pattern.compile("^([一二三四五六七八九十百千万]+)、\\s*(.+)$");
    private static final Pattern MARKDOWN_HEADING_WITH_DECIMAL = Pattern.compile("^(#+)\\s*([0-9]+(?:\\.[0-9]+)+)\\s+(.+)$");

    public String displayHeading(String heading) {
        String normalized = normalizeHeading(heading);
        String stripped = stripLeadingOrdinal(normalized);
        return stripped.isBlank() ? normalized : stripped;
    }

    public String trimDuplicatedHeading(String body, String heading) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String normalizedHeading = normalizeHeading(heading);
        String displayHeading = displayHeading(heading);
        String normalizedBody = body.strip();
        if (normalizedHeading.isBlank()) {
            return normalizedBody;
        }
        if (normalizedBody.startsWith("## " + normalizedHeading)) {
            return normalizedBody.substring(("## " + normalizedHeading).length()).stripLeading();
        }
        if (!displayHeading.isBlank() && normalizedBody.startsWith("## " + displayHeading)) {
            return normalizedBody.substring(("## " + displayHeading).length()).stripLeading();
        }
        return normalizedBody;
    }

    public String normalizeBodyStructure(String body, String sectionPrefix) {
        if (body == null || body.isBlank()) {
            return "";
        }
        List<String> normalizedLines = new ArrayList<>();
        for (String rawLine : body.strip().split("\\R")) {
            normalizedLines.add(normalizeBodyLine(rawLine, sectionPrefix));
        }
        return String.join("\n", normalizedLines).strip();
    }

    public String stripLeadingOrdinal(String heading) {
        String stripped = normalizeHeading(heading);
        String previous;
        do {
            previous = stripped;
            stripped = LEADING_SECTION_ORDINAL.matcher(stripped).replaceFirst("").stripLeading();
        } while (!stripped.equals(previous));
        return stripped;
    }

    public String normalizeHeading(String heading) {
        if (heading == null) {
            return "";
        }
        return LEADING_HEADING_MARKS.matcher(heading.strip()).replaceFirst("").strip();
    }

    private String normalizeBodyLine(String line, String sectionPrefix) {
        String trimmed = line == null ? "" : line.strip();
        if (trimmed.isBlank()) {
            return "";
        }
        Matcher decimalMatcher = PLAIN_DECIMAL_SECTION.matcher(trimmed);
        if (decimalMatcher.matches()) {
            String numbering = rewriteSectionNumber(decimalMatcher.group(1), sectionPrefix);
            String title = stripLeadingOrdinal(decimalMatcher.group(2));
            int depth = Math.min(6, 1 + numbering.split("\\.").length);
            return "#".repeat(depth) + " " + numbering + " " + title;
        }
        Matcher chineseMatcher = PLAIN_CHINESE_SECTION.matcher(trimmed);
        if (chineseMatcher.matches()) {
            return "### " + chineseMatcher.group(1) + "、" + stripLeadingOrdinal(chineseMatcher.group(2));
        }
        if (trimmed.startsWith("#")) {
            Matcher markdownDecimalMatcher = MARKDOWN_HEADING_WITH_DECIMAL.matcher(trimmed);
            if (markdownDecimalMatcher.matches()) {
                String numbering = rewriteSectionNumber(markdownDecimalMatcher.group(2), sectionPrefix);
                String title = stripLeadingOrdinal(markdownDecimalMatcher.group(3));
                int depth = Math.min(6, 1 + numbering.split("\\.").length);
                return "#".repeat(depth) + " " + numbering + " " + title;
            }
            int headingEnd = 0;
            while (headingEnd < trimmed.length() && trimmed.charAt(headingEnd) == '#') {
                headingEnd++;
            }
            String title = trimmed.substring(headingEnd).strip();
            return trimmed.substring(0, headingEnd) + " " + title;
        }
        return line == null ? "" : line.stripTrailing();
    }

    private String rewriteSectionNumber(String originalNumber, String sectionPrefix) {
        if (sectionPrefix == null || sectionPrefix.isBlank()) {
            return originalNumber;
        }
        String[] originalSegments = originalNumber.split("\\.");
        String[] prefixSegments = sectionPrefix.split("\\.");
        if (originalSegments.length <= 1) {
            return sectionPrefix;
        }
        List<String> result = new ArrayList<>(List.of(prefixSegments));
        for (int index = 1; index < originalSegments.length; index++) {
            result.add(originalSegments[index]);
        }
        return String.join(".", result);
    }
}
