package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.vo.PresentationIterationVO;
import com.lark.imcollab.skills.lark.slides.LarkSlidesFetchResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
@Service
public class PresentationIterationExecutionService implements PresentationIterationFacade {

    private static final Pattern PAGE_PATTERN = Pattern.compile("第\\s*(\\d{1,2})\\s*页");
    private static final Pattern CHINESE_PAGE_PATTERN = Pattern.compile("第\\s*([一二三四五六七八九十]{1,3})\\s*页");
    private static final Pattern TITLE_TO_PATTERN = Pattern.compile("(?:标题|题目)?(?:改成|改为|换成)[:：]?\\s*([^，。\\n]+)");

    private final LarkSlidesTool larkSlidesTool;
    private final PresentationEditIntentFacade intentFacade;

    public PresentationIterationExecutionService(
            LarkSlidesTool larkSlidesTool,
            PresentationEditIntentFacade intentFacade
    ) {
        this.larkSlidesTool = larkSlidesTool;
        this.intentFacade = intentFacade;
    }

    @Override
    public PresentationIterationVO edit(PresentationIterationRequest request) {
        requireValue(request == null ? null : request.getTaskId(), "taskId");
        String presentation = firstNonBlank(request.getPresentationId(), request.getPresentationUrl());
        requireValue(presentation, "presentationId/presentationUrl");
        String instruction = firstNonBlank(request.getInstruction(), "补充用户修改说明");
        PresentationEditIntent intent = intentFacade == null ? null : intentFacade.resolve(instruction);
        if (intent != null && intent.isClarificationNeeded()) {
            throw new IllegalArgumentException(firstNonBlank(intent.getClarificationHint(), "请明确要改第几页和改成什么内容"));
        }
        LarkSlidesFetchResult before = larkSlidesTool.fetchPresentation(presentation);
        SlideTarget target = resolveTarget(before.getXml(), instruction, intent);
        String newText = resolveReplacementText(instruction, intent);
        Map<String, Object> part = target.blockId() == null || target.blockId().isBlank()
                ? insertPart(newText)
                : replacePart(target.blockId(), newText, target.shape());
        larkSlidesTool.replaceSlide(presentation, target.slideId(), List.of(part));
        LarkSlidesFetchResult after = larkSlidesTool.fetchPresentation(presentation);
        if (!containsText(after.getXml(), newText)) {
            throw new IllegalStateException("PPT update verification failed: target text not found after replace");
        }
        return PresentationIterationVO.builder()
                .taskId(request.getTaskId())
                .artifactId(request.getArtifactId())
                .presentationId(firstNonBlank(request.getPresentationId(), after.getPresentationId(), presentation))
                .presentationUrl(request.getPresentationUrl())
                .summary("已修改 PPT 第 " + target.pageIndex() + " 页：" + newText)
                .modifiedSlides(List.of(target.slideId()))
                .build();
    }

    private SlideTarget resolveTarget(String xml, String instruction, PresentationEditIntent intent) {
        Document document = parseXml(xml);
        NodeList slides = document.getElementsByTagName("slide");
        if (slides.getLength() == 0) {
            throw new IllegalStateException("No slides found in presentation XML");
        }
        int pageIndex = intent != null && intent.getPageIndex() != null
                ? intent.getPageIndex()
                : requestedPage(instruction);
        int slideIndex = Math.min(Math.max(pageIndex, 1), slides.getLength()) - 1;
        Element slide = (Element) slides.item(slideIndex);
        String slideId = firstNonBlank(slide.getAttribute("id"));
        requireValue(slideId, "slide id");
        Element shape = findTitleShape(slide);
        return new SlideTarget(slideId, slideIndex + 1, shape == null ? null : shape.getAttribute("id"), shape);
    }

    private String resolveReplacementText(String instruction, PresentationEditIntent intent) {
        if (intent != null && firstNonBlank(intent.getReplacementText()) != null) {
            return intent.getReplacementText().trim();
        }
        return extractReplacementText(instruction);
    }

    private Element findTitleShape(Element slide) {
        NodeList shapes = slide.getElementsByTagName("shape");
        Element firstTextShape = null;
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            if (!"text".equalsIgnoreCase(shape.getAttribute("type"))) {
                continue;
            }
            if (firstTextShape == null) {
                firstTextShape = shape;
            }
            NodeList contents = shape.getElementsByTagName("content");
            for (int j = 0; j < contents.getLength(); j++) {
                Element content = (Element) contents.item(j);
                if ("title".equalsIgnoreCase(content.getAttribute("textType"))) {
                    return shape;
                }
            }
        }
        return firstTextShape;
    }

    private Map<String, Object> replacePart(String blockId, String newText, Element originalShape) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("action", "block_replace");
        part.put("block_id", blockId);
        part.put("replacement", buildTextShape(newText, originalShape, "title"));
        return part;
    }

    private Map<String, Object> insertPart(String newText) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("action", "block_insert");
        part.put("insertion", """
                <shape type="text" topLeftX="80" topLeftY="420" width="800" height="80">
                  <content textType="body"><p>%s</p></content>
                </shape>
                """.formatted(escapeXml(newText)).trim());
        return part;
    }

    private String buildTextShape(String newText, Element originalShape, String textType) {
        String x = firstNonBlank(attribute(originalShape, "topLeftX"), "80");
        String y = firstNonBlank(attribute(originalShape, "topLeftY"), "80");
        String width = firstNonBlank(attribute(originalShape, "width"), "800");
        String height = firstNonBlank(attribute(originalShape, "height"), "120");
        return """
                <shape type="text" topLeftX="%s" topLeftY="%s" width="%s" height="%s">
                  <content textType="%s"><p>%s</p></content>
                </shape>
                """.formatted(x, y, width, height, textType, escapeXml(newText)).trim();
    }

    private String extractReplacementText(String instruction) {
        Matcher matcher = TITLE_TO_PATTERN.matcher(instruction == null ? "" : instruction);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return instruction == null ? "用户补充说明" : instruction.trim();
    }

    private int requestedPage(String instruction) {
        Matcher matcher = PAGE_PATTERN.matcher(instruction == null ? "" : instruction);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        matcher = CHINESE_PAGE_PATTERN.matcher(instruction == null ? "" : instruction);
        if (matcher.find()) {
            Integer page = chineseNumber(matcher.group(1));
            if (page != null) {
                return page;
            }
        }
        return 1;
    }

    private Integer chineseNumber(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim()) {
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            default -> null;
        };
    }

    private Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to parse presentation XML", exception);
        }
    }

    private boolean containsText(String xml, String text) {
        return xml != null && text != null && (xml.contains(text) || xml.contains(escapeXml(text)));
    }

    private String attribute(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value;
    }

    private String escapeXml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private void requireValue(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
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

    private record SlideTarget(String slideId, int pageIndex, String blockId, Element shape) {
    }
}
