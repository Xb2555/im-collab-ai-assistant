package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
@Service
public class PresentationIterationExecutionService implements PresentationIterationFacade {

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
        List<PresentationEditOperation> operations = resolveOperations(instruction, intent);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("请明确要修改哪些页面，以及每页要改成什么内容");
        }
        LinkedHashSet<String> modifiedSlideIds = new LinkedHashSet<>();
        List<String> summarySegments = new ArrayList<>();
        for (PresentationEditOperation operation : operations) {
            LarkSlidesFetchResult before = larkSlidesTool.fetchPresentation(presentation);
            SlideTarget target = resolveTarget(before.getXml(), operation);
            Map<String, Object> part = buildPart(target, operation);
            larkSlidesTool.replaceSlide(presentation, target.slideId(), List.of(part));
            LarkSlidesFetchResult after = larkSlidesTool.fetchPresentation(presentation);
            if (requiresReplacement(operation) && !containsText(after.getXml(), operation.getReplacementText())) {
                throw new IllegalStateException("PPT update verification failed: target text not found after replace");
            }
            modifiedSlideIds.add(target.slideId());
            summarySegments.add(buildSummarySegment(target.pageIndex(), operation));
        }
        return PresentationIterationVO.builder()
                .taskId(request.getTaskId())
                .artifactId(request.getArtifactId())
                .presentationId(firstNonBlank(request.getPresentationId(), presentation))
                .presentationUrl(request.getPresentationUrl())
                .summary(joinSummary(summarySegments))
                .modifiedSlides(new ArrayList<>(modifiedSlideIds))
                .build();
    }

    private SlideTarget resolveTarget(String xml, PresentationEditOperation operation) {
        Document document = parseXml(xml);
        NodeList slides = document.getElementsByTagName("slide");
        if (slides.getLength() == 0) {
            throw new IllegalStateException("No slides found in presentation XML");
        }
        Integer pageIndex = operation.getPageIndex();
        if (pageIndex == null) {
            throw new IllegalArgumentException("请明确要改第几页");
        }
        int slideIndex = Math.min(Math.max(pageIndex, 1), slides.getLength()) - 1;
        Element slide = (Element) slides.item(slideIndex);
        String slideId = firstNonBlank(slide.getAttribute("id"));
        requireValue(slideId, "slide id");
        Element shape = findTargetShape(slide, operation.getTargetElementType());
        return new SlideTarget(slideId, slideIndex + 1, shape == null ? null : shape.getAttribute("id"), shape);
    }

    private Element findTargetShape(Element slide, PresentationTargetElementType targetElementType) {
        NodeList shapes = slide.getElementsByTagName("shape");
        Element firstTextShape = null;
        Element firstBodyShape = null;
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
                    if (targetElementType == PresentationTargetElementType.TITLE) {
                        return shape;
                    }
                }
                if ("body".equalsIgnoreCase(content.getAttribute("textType"))) {
                    if (firstBodyShape == null) {
                        firstBodyShape = shape;
                    }
                    if (targetElementType == PresentationTargetElementType.BODY) {
                        return shape;
                    }
                }
            }
        }
        if (targetElementType == PresentationTargetElementType.BODY && firstBodyShape != null) {
            return firstBodyShape;
        }
        return firstTextShape;
    }

    private Map<String, Object> buildPart(SlideTarget target, PresentationEditOperation operation) {
        if (operation.getActionType() != PresentationEditActionType.REPLACE_SLIDE_TITLE
                && operation.getActionType() != PresentationEditActionType.REPLACE_SLIDE_BODY) {
            throw new IllegalArgumentException("暂不支持的 PPT 修改动作: " + operation.getActionType());
        }
        String newText = firstNonBlank(operation.getReplacementText());
        String textType = operation.getTargetElementType() == PresentationTargetElementType.BODY ? "body" : "title";
        if (target.blockId() == null || target.blockId().isBlank()) {
            return insertPart(newText, textType);
        }
        return replacePart(target.blockId(), newText, target.shape(), textType);
    }

    private Map<String, Object> replacePart(String blockId, String newText, Element originalShape, String textType) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("action", "block_replace");
        part.put("block_id", blockId);
        part.put("replacement", buildTextShape(newText, originalShape, textType));
        return part;
    }

    private Map<String, Object> insertPart(String newText, String textType) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("action", "block_insert");
        part.put("insertion", """
                <shape type="text" topLeftX="80" topLeftY="420" width="800" height="80">
                  <content textType="%s"><p>%s</p></content>
                </shape>
                """.formatted(textType, escapeXml(newText)).trim());
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

    private List<PresentationEditOperation> resolveOperations(String instruction, PresentationEditIntent intent) {
        if (intent != null && intent.getOperations() != null && !intent.getOperations().isEmpty()) {
            return intent.getOperations();
        }
        if (intent != null && intent.getActionType() != null) {
            return List.of(PresentationEditOperation.builder()
                    .actionType(intent.getActionType())
                    .targetElementType(intent.getTargetElementType())
                    .pageIndex(intent.getPageIndex())
                    .replacementText(intent.getReplacementText())
                    .build());
        }
        return List.of();
    }

    private boolean requiresReplacement(PresentationEditOperation operation) {
        return operation.getActionType() == PresentationEditActionType.REPLACE_SLIDE_TITLE
                || operation.getActionType() == PresentationEditActionType.REPLACE_SLIDE_BODY;
    }

    private String buildSummarySegment(int pageIndex, PresentationEditOperation operation) {
        String label = operation.getTargetElementType() == PresentationTargetElementType.BODY ? "正文" : "标题";
        return "第 " + pageIndex + " 页" + label + "改为" + firstNonBlank(operation.getReplacementText(), "指定内容");
    }

    private String joinSummary(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return "PPT 已更新";
        }
        if (segments.size() == 1) {
            return "已修改 PPT " + segments.get(0);
        }
        return "已修改 PPT：" + String.join("；", segments);
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
