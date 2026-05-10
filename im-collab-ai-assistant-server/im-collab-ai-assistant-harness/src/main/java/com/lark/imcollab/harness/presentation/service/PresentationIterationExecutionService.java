package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.PresentationLayoutSpec;
import com.lark.imcollab.common.model.entity.PresentationSnapshot;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationEditability;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import com.lark.imcollab.common.model.vo.PresentationIterationVO;
import com.lark.imcollab.skills.lark.slides.LarkSlidesFetchResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesReplaceResult;
import com.lark.imcollab.skills.lark.slides.LarkSlidesTool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
@Service
public class PresentationIterationExecutionService implements PresentationIterationFacade {

    private final LarkSlidesTool larkSlidesTool;
    private final PresentationEditIntentFacade intentFacade;
    private final PresentationBodyRewriteService bodyRewriteService;

    @Autowired
    public PresentationIterationExecutionService(
            LarkSlidesTool larkSlidesTool,
            PresentationEditIntentFacade intentFacade,
            PresentationBodyRewriteService bodyRewriteService
    ) {
        this.larkSlidesTool = larkSlidesTool;
        this.intentFacade = intentFacade;
        this.bodyRewriteService = bodyRewriteService;
    }

    public PresentationIterationExecutionService(
            LarkSlidesTool larkSlidesTool,
            PresentationEditIntentFacade intentFacade
    ) {
        this(larkSlidesTool, intentFacade, null);
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
            OperationResult operationResult = executeOperation(presentation, before.getXml(), operation);
            LarkSlidesFetchResult after = larkSlidesTool.fetchPresentation(presentation);
            for (String verificationText : operationResult.verificationTexts()) {
                if (hasText(verificationText) && !containsText(after.getXml(), verificationText)) {
                    throw new IllegalStateException("PPT update verification failed: target text not found after edit");
                }
            }
            modifiedSlideIds.addAll(operationResult.modifiedSlideIds());
            summarySegments.add(operationResult.summarySegment());
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

    private OperationResult executeOperation(String presentation, String xml, PresentationEditOperation operation) {
        if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE) {
            return insertSlide(presentation, xml, operation);
        }
        if (operation.getActionType() == PresentationEditActionType.DELETE_SLIDE) {
            return deleteSlide(presentation, xml, operation);
        }
        if (operation.getActionType() == PresentationEditActionType.MOVE_SLIDE) {
            return moveSlide(presentation, xml, operation);
        }
        SlideTarget target = resolveTarget(xml, operation);
        PresentationEditOperation executableOperation = materializeOperation(target, operation);
        Map<String, Object> part = buildPart(target, executableOperation);
        larkSlidesTool.replaceSlide(presentation, target.slideId(), List.of(part));
        return new OperationResult(
                List.of(target.slideId()),
                verificationTexts(executableOperation),
                buildSummarySegment(target.pageIndex(), executableOperation)
        );
    }

    private OperationResult insertSlide(String presentation, String xml, PresentationEditOperation operation) {
        Deck deck = parseDeck(xml);
        InsertPosition position = resolveInsertPosition(deck, insertAfterPageIndex(operation));
        SlideInfo template = chooseTemplateSlide(deck, position.templateIndex());
        String templateXml = firstNonBlank(
                larkSlidesTool.fetchSlide(presentation, template.slideId()).getXml(),
                serializeElement(template.element())
        );
        String slideXml = buildClonedSlideXml(templateXml, operation);
        LarkSlidesReplaceResult result = larkSlidesTool.createSlide(presentation, slideXml, position.beforeSlideId());
        return new OperationResult(
                List.of(firstNonBlank(result.getSlideId(), "inserted-after-" + position.insertAfterLabel())),
                verificationTexts(operation),
                buildSummarySegment(position.insertAfterPageIndex(), operation)
        );
    }

    private Integer insertAfterPageIndex(PresentationEditOperation operation) {
        if (operation == null) {
            return null;
        }
        if (operation.getInsertAfterPageIndex() != null) {
            return operation.getInsertAfterPageIndex();
        }
        return operation.getActionType() == PresentationEditActionType.INSERT_SLIDE ? operation.getPageIndex() : null;
    }

    private OperationResult deleteSlide(String presentation, String xml, PresentationEditOperation operation) {
        Deck deck = parseDeck(xml);
        if (deck.slides().size() <= 1) {
            throw new IllegalArgumentException("不能删除唯一一页 PPT");
        }
        SlideInfo target = slideAtPage(deck, operation.getPageIndex());
        larkSlidesTool.deleteSlide(presentation, target.slideId());
        return new OperationResult(
                List.of(target.slideId()),
                List.of(),
                buildSummarySegment(target.pageIndex(), operation)
        );
    }

    private OperationResult moveSlide(String presentation, String xml, PresentationEditOperation operation) {
        Deck deck = parseDeck(xml);
        SlideInfo source = slideAtPage(deck, operation.getPageIndex());
        if (operation.getInsertAfterPageIndex() == null) {
            throw new IllegalArgumentException("请明确要把该页移动到第几页之后");
        }
        int insertAfterPageIndex = normalizeMoveInsertAfterPageIndex(deck, operation.getInsertAfterPageIndex());
        String sourceXml = firstNonBlank(
                larkSlidesTool.fetchSlide(presentation, source.slideId()).getXml(),
                serializeElement(source.element())
        );
        String slideXml = buildClonedSlideXml(sourceXml, operation);
        String beforeSlideId = resolveMoveBeforeSlideId(deck, insertAfterPageIndex);
        LarkSlidesReplaceResult created = larkSlidesTool.createSlide(presentation, slideXml, beforeSlideId);
        larkSlidesTool.deleteSlide(presentation, source.slideId());
        PresentationEditOperation summaryOperation = PresentationEditOperation.builder()
                .actionType(operation.getActionType())
                .targetElementType(operation.getTargetElementType())
                .pageIndex(operation.getPageIndex())
                .insertAfterPageIndex(insertAfterPageIndex)
                .slideTitle(operation.getSlideTitle())
                .slideBody(operation.getSlideBody())
                .replacementText(operation.getReplacementText())
                .build();
        return new OperationResult(
                List.of(firstNonBlank(created.getSlideId(), source.slideId())),
                verificationTexts(operation),
                buildSummarySegment(source.pageIndex(), summaryOperation)
        );
    }

    private int normalizeMoveInsertAfterPageIndex(Deck deck, int insertAfterPageIndex) {
        if (insertAfterPageIndex < 0) {
            return deck.slides().size();
        }
        return insertAfterPageIndex;
    }

    private SlideTarget resolveTarget(String xml, PresentationEditOperation operation) {
        if (operation != null
                && operation.getPageIndex() == null
                && operation.getAnchorMode() == PresentationAnchorMode.BY_QUOTED_TEXT
                && hasText(operation.getQuotedText())) {
            return resolveDeckWideQuotedTarget(xml, operation);
        }
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
        List<PresentationSnapshot> snapshots = buildSnapshots(slide, slideIndex + 1);
        PresentationSnapshot snapshot = resolveSnapshotTarget(snapshots, operation);
        if (snapshot == null && operation.getAnchorMode() != null && operation.getAnchorMode() != PresentationAnchorMode.BY_PAGE_INDEX) {
            throw new IllegalArgumentException("无法唯一定位到要修改的 PPT 元素，请补充更具体的页内锚点");
        }
        Element shape = snapshot == null ? findTargetShape(slide, operation.getTargetElementType()) : findShapeById(slide, snapshot.getBlockId());
        if (shape == null) {
            throw new IllegalArgumentException("无法定位到要修改的 PPT 元素，请补充更具体的修改位置");
        }
        return new SlideTarget(
                slideId,
                slideIndex + 1,
                snapshot == null ? (shape == null ? null : shape.getAttribute("id")) : snapshot.getBlockId(),
                shape);
    }

    private SlideTarget resolveDeckWideQuotedTarget(String xml, PresentationEditOperation operation) {
        Deck deck = parseDeck(xml);
        List<SlideTarget> matches = new ArrayList<>();
        for (SlideInfo slideInfo : deck.slides()) {
            List<PresentationSnapshot> snapshots = buildSnapshots(slideInfo.element(), slideInfo.pageIndex());
            PresentationSnapshot snapshot;
            try {
                snapshot = resolveSnapshotTarget(snapshots, operation);
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            if (snapshot == null) {
                continue;
            }
            Element shape = findShapeById(slideInfo.element(), snapshot.getBlockId());
            if (shape == null) {
                continue;
            }
            matches.add(new SlideTarget(
                    slideInfo.slideId(),
                    slideInfo.pageIndex(),
                    snapshot.getBlockId(),
                    shape));
        }
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("无法根据引用文本定位到要修改的 PPT 元素，请补充页码或更完整的原文");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("引用文本在多页中重复出现，请补充页码后再修改");
        }
        return matches.get(0);
    }

    private Deck parseDeck(String xml) {
        Document document = parseXml(xml);
        NodeList nodes = document.getElementsByTagName("slide");
        if (nodes.getLength() == 0 && "slide".equalsIgnoreCase(document.getDocumentElement().getTagName())) {
            nodes = document.getChildNodes();
        }
        List<SlideInfo> slides = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element slide) || !"slide".equalsIgnoreCase(slide.getTagName())) {
                continue;
            }
            String slideId = firstNonBlank(slide.getAttribute("id"), slide.getAttribute("slide_id"), slide.getAttribute("slideId"));
            requireValue(slideId, "slide id");
            slides.add(new SlideInfo(slideId, slides.size() + 1, slide));
        }
        if (slides.isEmpty()) {
            throw new IllegalStateException("No slides found in presentation XML");
        }
        return new Deck(slides);
    }

    private SlideInfo slideAtPage(Deck deck, Integer pageIndex) {
        if (pageIndex == null) {
            throw new IllegalArgumentException("请明确要操作第几页");
        }
        if (pageIndex < 1 || pageIndex > deck.slides().size()) {
            throw new IllegalArgumentException("PPT 中不存在第 " + pageIndex + " 页");
        }
        return deck.slides().get(pageIndex - 1);
    }

    private InsertPosition resolveInsertPosition(Deck deck, Integer insertAfterPageIndex) {
        int slideCount = deck.slides().size();
        if (insertAfterPageIndex == null || insertAfterPageIndex >= slideCount) {
            return new InsertPosition(null, slideCount - 1, slideCount, "末尾");
        }
        if (insertAfterPageIndex <= 0) {
            return new InsertPosition(deck.slides().get(0).slideId(), 0, 0, "最前");
        }
        return new InsertPosition(
                deck.slides().get(insertAfterPageIndex).slideId(),
                insertAfterPageIndex - 1,
                insertAfterPageIndex,
                String.valueOf(insertAfterPageIndex)
        );
    }

    private String resolveMoveBeforeSlideId(Deck deck, int insertAfterPageIndex) {
        if (insertAfterPageIndex <= 0) {
            return deck.slides().get(0).slideId();
        }
        if (insertAfterPageIndex >= deck.slides().size()) {
            return null;
        }
        return deck.slides().get(insertAfterPageIndex).slideId();
    }

    private SlideInfo chooseTemplateSlide(Deck deck, int preferredIndex) {
        int normalizedIndex = Math.min(Math.max(preferredIndex, 0), deck.slides().size() - 1);
        SlideInfo preferred = deck.slides().get(normalizedIndex);
        if (isReusableBodyTemplate(preferred)) {
            return preferred;
        }
        SlideInfo closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (SlideInfo slide : deck.slides()) {
            if (!isReusableBodyTemplate(slide)) {
                continue;
            }
            int distance = Math.abs(slide.pageIndex() - preferred.pageIndex());
            if (distance < closestDistance) {
                closest = slide;
                closestDistance = distance;
            }
        }
        return closest == null ? preferred : closest;
    }

    private boolean isReusableBodyTemplate(SlideInfo slide) {
        String title = textContent(findTargetShape(slide.element(), PresentationTargetElementType.TITLE));
        if (containsAny(title, "封面", "目录", "总结", "结语", "致谢", "谢谢", "Q&A")) {
            return false;
        }
        return findBodyReplacementShape(slide.element()) != null;
    }

    private Element findTargetShape(Element slide, PresentationTargetElementType targetElementType) {
        if (targetElementType == PresentationTargetElementType.IMAGE) {
            NodeList images = slide.getElementsByTagName("img");
            return images.getLength() == 0 ? null : (Element) images.item(0);
        }
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

    private Element findExactTextShape(Element slide, PresentationTargetElementType targetElementType) {
        String expected = targetElementType == PresentationTargetElementType.BODY ? "body" : "title";
        NodeList shapes = slide.getElementsByTagName("shape");
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            if (!"text".equalsIgnoreCase(shape.getAttribute("type"))) {
                continue;
            }
            NodeList contents = shape.getElementsByTagName("content");
            for (int j = 0; j < contents.getLength(); j++) {
                Element content = (Element) contents.item(j);
                if (expected.equalsIgnoreCase(content.getAttribute("textType"))) {
                    return shape;
                }
            }
        }
        return null;
    }

    private Element findBodyReplacementShape(Element slide) {
        Element exactBody = findExactTextShape(slide, PresentationTargetElementType.BODY);
        if (exactBody != null) {
            return exactBody;
        }
        Element titleShape = findTargetShape(slide, PresentationTargetElementType.TITLE);
        NodeList shapes = slide.getElementsByTagName("shape");
        Element emptyTextPlaceholder = null;
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            if (!"text".equalsIgnoreCase(shape.getAttribute("type")) || shape == titleShape || hasTitleContent(shape)) {
                continue;
            }
            if (hasText(shape.getTextContent())) {
                return shape;
            }
            if (emptyTextPlaceholder == null) {
                emptyTextPlaceholder = shape;
            }
        }
        return emptyTextPlaceholder;
    }

    private boolean hasTitleContent(Element shape) {
        NodeList contents = shape.getElementsByTagName("content");
        for (int i = 0; i < contents.getLength(); i++) {
            Element content = (Element) contents.item(i);
            if ("title".equalsIgnoreCase(content.getAttribute("textType"))) {
                return true;
            }
        }
        return false;
    }

    private String buildClonedSlideXml(String templateXml, PresentationEditOperation operation) {
        Document document = parseXml(templateXml);
        Element slide = findSlideElement(document);
        stripServerIds(slide);
        String title = firstNonBlank(operation.getSlideTitle(), titleFromReplacement(operation));
        String body = firstNonBlank(operation.getSlideBody(), bodyFromReplacement(operation));
        replaceOrInsertSlideText(slide, PresentationTargetElementType.TITLE, title);
        replaceOrInsertSlideText(slide, PresentationTargetElementType.BODY, body);
        removeEmptyTextShapes(slide);
        return serializeElement(slide);
    }

    private Element findSlideElement(Document document) {
        Element root = document.getDocumentElement();
        if (root != null && "slide".equalsIgnoreCase(root.getTagName())) {
            return root;
        }
        NodeList slides = document.getElementsByTagName("slide");
        if (slides.getLength() == 0) {
            throw new IllegalStateException("No slide found in slide XML");
        }
        return (Element) slides.item(0);
    }

    private void replaceOrInsertSlideText(Element slide, PresentationTargetElementType targetType, String text) {
        if (!hasText(text)) {
            return;
        }
        Element shape = targetType == PresentationTargetElementType.BODY
                ? findBodyReplacementShape(slide)
                : findTargetShape(slide, targetType);
        if (shape == null) {
            appendDefaultTextShape(slide, targetType, text);
            return;
        }
        Element content = findContent(shape, targetType);
        if (content == null) {
            content = shape.getOwnerDocument().createElement("content");
            content.setAttribute("textType", targetType == PresentationTargetElementType.BODY ? "body" : "title");
            shape.appendChild(content);
        }
        replaceContentChildren(content, targetType, text);
    }

    private Element findContent(Element shape, PresentationTargetElementType targetType) {
        String expected = targetType == PresentationTargetElementType.BODY ? "body" : "title";
        NodeList contents = shape.getElementsByTagName("content");
        Element first = null;
        for (int i = 0; i < contents.getLength(); i++) {
            Element content = (Element) contents.item(i);
            if (first == null) {
                first = content;
            }
            if (expected.equalsIgnoreCase(content.getAttribute("textType"))) {
                return content;
            }
        }
        return first;
    }

    private void replaceContentChildren(Element content, PresentationTargetElementType targetType, String text) {
        while (content.hasChildNodes()) {
            content.removeChild(content.getFirstChild());
        }
        Document document = content.getOwnerDocument();
        List<String> items = splitBodyItems(text);
        if (targetType == PresentationTargetElementType.BODY && items.size() > 1) {
            Element ul = document.createElement("ul");
            for (String item : items) {
                Element li = document.createElement("li");
                Element p = document.createElement("p");
                p.appendChild(document.createTextNode(item));
                li.appendChild(p);
                ul.appendChild(li);
            }
            content.appendChild(ul);
            return;
        }
        Element p = document.createElement("p");
        p.appendChild(document.createTextNode(text));
        content.appendChild(p);
    }

    private void appendDefaultTextShape(Element slide, PresentationTargetElementType targetType, String text) {
        Document document = slide.getOwnerDocument();
        Element neighbor = findTargetShape(slide, PresentationTargetElementType.TITLE);
        Element shape = document.createElement("shape");
        shape.setAttribute("type", "text");
        shape.setAttribute("topLeftX", firstNonBlank(attribute(neighbor, "topLeftX"), "80"));
        shape.setAttribute("topLeftY", targetType == PresentationTargetElementType.BODY
                ? offset(attribute(neighbor, "topLeftY"), attribute(neighbor, "height"), 40, "260")
                : firstNonBlank(attribute(neighbor, "topLeftY"), "80"));
        shape.setAttribute("width", firstNonBlank(attribute(neighbor, "width"), "800"));
        shape.setAttribute("height", targetType == PresentationTargetElementType.BODY ? "240" : "120");
        Element content = document.createElement("content");
        content.setAttribute("textType", targetType == PresentationTargetElementType.BODY ? "body" : "title");
        replaceContentChildren(content, targetType, text);
        shape.appendChild(content);
        Element data = firstChildElement(slide, "data");
        (data == null ? slide : data).appendChild(shape);
    }

    private Element firstChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element && name.equalsIgnoreCase(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private void removeEmptyTextShapes(Element slide) {
        NodeList shapes = slide.getElementsByTagName("shape");
        for (int i = shapes.getLength() - 1; i >= 0; i--) {
            Element shape = (Element) shapes.item(i);
            if (!"text".equalsIgnoreCase(shape.getAttribute("type")) || hasText(shape.getTextContent())) {
                continue;
            }
            Node parent = shape.getParentNode();
            if (parent != null) {
                parent.removeChild(shape);
            }
        }
    }

    private String offset(String y, String height, int gap, String fallback) {
        try {
            double value = Double.parseDouble(firstNonBlank(y, "80"));
            double size = Double.parseDouble(firstNonBlank(height, "120"));
            return String.valueOf((int) Math.round(value + size + gap));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private List<String> splitBodyItems(String text) {
        if (!hasText(text)) {
            return List.of();
        }
        String[] parts = text.split("[\\n；;、,，]+");
        List<String> items = new ArrayList<>();
        for (String part : parts) {
            if (hasText(part)) {
                items.add(part.trim());
            }
        }
        return items.size() <= 1 ? List.of(text.trim()) : items;
    }

    private void stripServerIds(Node node) {
        if (node instanceof Element element) {
            NamedNodeMap attributes = element.getAttributes();
            for (int i = attributes.getLength() - 1; i >= 0; i--) {
                Node attribute = attributes.item(i);
                String name = attribute.getNodeName();
                String normalized = name == null ? "" : name.toLowerCase();
                if ("id".equals(normalized)
                        || normalized.endsWith("_id")
                        || normalized.endsWith("-id")
                        || normalized.endsWith("id")) {
                    element.removeAttribute(name);
                }
            }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            stripServerIds(children.item(i));
        }
    }

    private String serializeElement(Element element) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(element), new StreamResult(writer));
            return writer.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize slide XML", exception);
        }
    }

    private Map<String, Object> buildPart(SlideTarget target, PresentationEditOperation operation) {
        if (operation.getActionType() != PresentationEditActionType.REPLACE_SLIDE_TITLE
                && operation.getActionType() != PresentationEditActionType.REPLACE_SLIDE_BODY
                && operation.getActionType() != PresentationEditActionType.REWRITE_ELEMENT
                && operation.getActionType() != PresentationEditActionType.EXPAND_ELEMENT
                && operation.getActionType() != PresentationEditActionType.SHORTEN_ELEMENT
                && operation.getActionType() != PresentationEditActionType.REPLACE_ELEMENT
                && operation.getActionType() != PresentationEditActionType.REPLACE_IMAGE) {
            throw new IllegalArgumentException("暂不支持的 PPT 修改动作: " + operation.getActionType());
        }
        String newText = firstNonBlank(operation.getReplacementText());
        if (operation.getActionType() == PresentationEditActionType.REPLACE_IMAGE
                || operation.getTargetElementType() == PresentationTargetElementType.IMAGE) {
            return replaceImagePart(target, operation);
        }
        String textType = operation.getTargetElementType() == PresentationTargetElementType.BODY ? "body" : "title";
        if (target.blockId() == null || target.blockId().isBlank()) {
            return insertPart(newText, textType);
        }
        return replacePart(target.blockId(), newText, target.shape(), textType);
    }

    private PresentationEditOperation materializeOperation(SlideTarget target, PresentationEditOperation operation) {
        if (!needsGeneratedReplacement(operation)) {
            return operation;
        }
        if (bodyRewriteService == null) {
            throw new IllegalStateException("PPT 正文改写生成器未配置，无法自动改写正文");
        }
        String generated = bodyRewriteService.rewrite(textContent(target.shape()), operation);
        return PresentationEditOperation.builder()
                .actionType(operation.getActionType())
                .targetElementType(operation.getTargetElementType())
                .pageIndex(operation.getPageIndex())
                .targetSlideId(operation.getTargetSlideId())
                .insertAfterPageIndex(operation.getInsertAfterPageIndex())
                .slideTitle(operation.getSlideTitle())
                .slideBody(operation.getSlideBody())
                .replacementText(generated)
                .anchorMode(operation.getAnchorMode())
                .quotedText(operation.getQuotedText())
                .elementRole(operation.getElementRole())
                .expectedMatchCount(operation.getExpectedMatchCount())
                .contentInstruction(operation.getContentInstruction())
                .targetElementId(operation.getTargetElementId())
                .targetBlockId(operation.getTargetBlockId())
                .build();
    }

    private boolean needsGeneratedReplacement(PresentationEditOperation operation) {
        if (operation == null || hasText(operation.getReplacementText())) {
            return false;
        }
        if (operation.getTargetElementType() != PresentationTargetElementType.BODY) {
            return false;
        }
        return operation.getActionType() == PresentationEditActionType.REWRITE_ELEMENT
                || operation.getActionType() == PresentationEditActionType.EXPAND_ELEMENT
                || operation.getActionType() == PresentationEditActionType.SHORTEN_ELEMENT;
    }

    private Map<String, Object> replaceImagePart(SlideTarget target, PresentationEditOperation operation) {
        String replacement = firstNonBlank(operation.getReplacementText(), operation.getContentInstruction(), "替换图片");
        String token = replacement.startsWith("boxcn") ? replacement : "boxcn-replaced-image";
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("action", "block_replace");
        part.put("block_id", firstNonBlank(target.blockId(), "image-block"));
        part.put("replacement", """
                <img src="%s" topLeftX="560" topLeftY="90" width="320" height="180" alt="%s"/>
                """.formatted(token, escapeXml(replacement)).trim());
        return part;
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

    private List<PresentationSnapshot> buildSnapshots(Element slide, int pageIndex) {
        List<PresentationSnapshot> snapshots = new ArrayList<>();
        NodeList shapes = slide.getElementsByTagName("shape");
        List<Element> bodyShapes = new ArrayList<>();
        List<Element> titleShapes = new ArrayList<>();
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            PresentationTargetElementType targetType = detectTargetType(shape);
            if (targetType == PresentationTargetElementType.TITLE) {
                titleShapes.add(shape);
            } else {
                bodyShapes.add(shape);
            }
        }
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            PresentationTargetElementType targetType = detectTargetType(shape);
            String semanticRole = resolveTextSemanticRole(shape, targetType, titleShapes, bodyShapes);
            String textContent = cleanText(shape.getTextContent());
            snapshots.add(PresentationSnapshot.builder()
                    .slideId(firstNonBlank(slide.getAttribute("id")))
                    .pageIndex(pageIndex)
                    .elementId(firstNonBlank(shape.getAttribute("id"), "shape-" + i))
                    .blockId(firstNonBlank(shape.getAttribute("id"), "shape-" + i))
                    .elementKind(targetType == PresentationTargetElementType.TITLE ? PresentationElementKind.TITLE : PresentationElementKind.BODY)
                    .textType(targetType == PresentationTargetElementType.TITLE ? "title" : "body")
                    .textContent(textContent)
                    .normalizedText(normalizeText(textContent))
                    .boundingBox(PresentationLayoutSpec.builder()
                            .topLeftX(parseInteger(shape.getAttribute("topLeftX")))
                            .topLeftY(parseInteger(shape.getAttribute("topLeftY")))
                            .width(parseInteger(shape.getAttribute("width")))
                            .height(parseInteger(shape.getAttribute("height")))
                            .build())
                    .semanticRole(semanticRole)
                    .editability(PresentationEditability.NATIVE_EDITABLE)
                    .build());
        }
        NodeList images = slide.getElementsByTagName("img");
        List<Element> imageElements = new ArrayList<>();
        for (int i = 0; i < images.getLength(); i++) {
            imageElements.add((Element) images.item(i));
        }
        for (int i = 0; i < images.getLength(); i++) {
            Element image = (Element) images.item(i);
            String imageText = firstNonBlank(image.getAttribute("alt"), image.getAttribute("src"));
            snapshots.add(PresentationSnapshot.builder()
                    .slideId(firstNonBlank(slide.getAttribute("id")))
                    .pageIndex(pageIndex)
                    .elementId(firstNonBlank(image.getAttribute("id"), "img-" + i))
                    .blockId(firstNonBlank(image.getAttribute("id"), "img-" + i))
                    .elementKind(PresentationElementKind.IMAGE)
                    .textType("image")
                    .textContent(imageText)
                    .normalizedText(normalizeText(imageText))
                    .boundingBox(PresentationLayoutSpec.builder()
                            .topLeftX(parseInteger(image.getAttribute("topLeftX")))
                            .topLeftY(parseInteger(image.getAttribute("topLeftY")))
                            .width(parseInteger(image.getAttribute("width")))
                            .height(parseInteger(image.getAttribute("height")))
                            .build())
                    .semanticRole(resolveImageSemanticRole(image, imageElements))
                    .editability(PresentationEditability.HYBRID_EDITABLE)
                    .build());
        }
        return snapshots;
    }

    private PresentationSnapshot resolveSnapshotTarget(List<PresentationSnapshot> snapshots, PresentationEditOperation operation) {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        List<PresentationSnapshot> typedCandidates = snapshots.stream()
                .filter(snapshot -> matchesTargetType(snapshot, operation.getTargetElementType()))
                .toList();
        if (typedCandidates.isEmpty()) {
            return null;
        }
        PresentationAnchorMode anchorMode = operation.getAnchorMode();
        if (anchorMode == PresentationAnchorMode.BY_QUOTED_TEXT && hasText(operation.getQuotedText())) {
            String quoted = normalizeText(operation.getQuotedText());
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> snapshot.getNormalizedText() != null && snapshot.getNormalizedText().contains(quoted))
                    .sorted(Comparator
                            .comparingInt((PresentationSnapshot snapshot) -> normalizedMatchDistance(snapshot.getNormalizedText(), quoted))
                            .thenComparingInt(snapshot -> snapshot.getBoundingBox() == null ? Integer.MAX_VALUE : snapshot.getBoundingBox().getTopLeftY()))
                    .toList(), operation, "引用文本");
        }
        if (anchorMode == PresentationAnchorMode.BY_ELEMENT_ROLE && hasText(operation.getElementRole())) {
            String expectedRole = normalizeText(operation.getElementRole()).toLowerCase();
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> roleMatches(snapshot.getSemanticRole(), expectedRole))
                    .sorted(Comparator
                            .comparingInt((PresentationSnapshot snapshot) -> roleSpecificityScore(snapshot.getSemanticRole(), expectedRole))
                            .thenComparingInt(snapshot -> snapshot.getBoundingBox() == null ? Integer.MAX_VALUE : snapshot.getBoundingBox().getTopLeftY()))
                    .toList(), operation, "元素角色");
        }
        if (anchorMode == PresentationAnchorMode.BY_BLOCK_ID && hasText(operation.getTargetBlockId())) {
            return resolveSingleCandidate(typedCandidates.stream()
                    .filter(snapshot -> Objects.equals(snapshot.getBlockId(), operation.getTargetBlockId()))
                    .toList(), operation, "blockId");
        }
        return resolveSingleCandidate(typedCandidates, operation, "页内元素类型");
    }

    private boolean matchesTargetType(PresentationSnapshot snapshot, PresentationTargetElementType targetElementType) {
        if (targetElementType == null || snapshot == null) {
            return true;
        }
        return switch (targetElementType) {
            case TITLE -> snapshot.getElementKind() == PresentationElementKind.TITLE;
            case BODY, CAPTION -> snapshot.getElementKind() == PresentationElementKind.BODY;
            case IMAGE -> snapshot.getElementKind() == PresentationElementKind.IMAGE;
            default -> true;
        };
    }

    private Element findShapeById(Element slide, String blockId) {
        if (!hasText(blockId)) {
            return null;
        }
        NodeList shapes = slide.getElementsByTagName("shape");
        for (int i = 0; i < shapes.getLength(); i++) {
            Element shape = (Element) shapes.item(i);
            if (blockId.equals(firstNonBlank(shape.getAttribute("id")))) {
                return shape;
            }
        }
        NodeList images = slide.getElementsByTagName("img");
        for (int i = 0; i < images.getLength(); i++) {
            Element image = (Element) images.item(i);
            if (blockId.equals(firstNonBlank(image.getAttribute("id")))) {
                return image;
            }
        }
        return null;
    }

    private PresentationTargetElementType detectTargetType(Element shape) {
        Element content = findContent(shape, PresentationTargetElementType.TITLE);
        if (content != null && "title".equalsIgnoreCase(content.getAttribute("textType"))) {
            return PresentationTargetElementType.TITLE;
        }
        return PresentationTargetElementType.BODY;
    }

    private String resolveTextSemanticRole(
            Element shape,
            PresentationTargetElementType targetType,
            List<Element> titleShapes,
            List<Element> bodyShapes
    ) {
        if (targetType == PresentationTargetElementType.TITLE) {
            return "title";
        }
        int ordinal = ordinalOf(bodyShapes, shape, Comparator
                .comparingInt((Element element) -> parseInteger(element.getAttribute("topLeftX")))
                .thenComparingInt(element -> parseInteger(element.getAttribute("topLeftY"))));
        return axisRole(shape, "body", ordinal);
    }

    private String resolveImageSemanticRole(Element image, List<Element> images) {
        int ordinal = ordinalOf(images, image, Comparator
                .comparingInt((Element element) -> parseInteger(element.getAttribute("topLeftX")))
                .thenComparingInt(element -> parseInteger(element.getAttribute("topLeftY"))));
        return axisRole(image, "image", ordinal);
    }

    private int ordinalOf(List<Element> elements, Element target, Comparator<Element> comparator) {
        if (elements == null || elements.isEmpty() || target == null) {
            return 1;
        }
        List<Element> ordered = new ArrayList<>(elements);
        ordered.sort(comparator);
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) == target) {
                return i + 1;
            }
        }
        return 1;
    }

    private String axisRole(Element element, String baseRole, int ordinal) {
        int x = parseInteger(element.getAttribute("topLeftX"));
        int y = parseInteger(element.getAttribute("topLeftY"));
        String horizontal = x >= 480 ? "right" : "left";
        String vertical = y >= 270 ? "bottom" : "top";
        return horizontal + "-" + baseRole + "-" + ordinal + "|" + horizontal + "-" + baseRole + "|" + vertical + "-" + baseRole + "|" + baseRole + "-" + ordinal + "|" + baseRole;
    }

    private PresentationSnapshot resolveSingleCandidate(
            List<PresentationSnapshot> candidates,
            PresentationEditOperation operation,
            String anchorLabel
    ) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        int expected = operation == null || operation.getExpectedMatchCount() == null ? 1 : operation.getExpectedMatchCount();
        if (candidates.size() > expected) {
            throw new IllegalArgumentException("PPT 页内" + anchorLabel + "命中不唯一，请补充更具体的修改位置");
        }
        return candidates.get(0);
    }

    private int normalizedMatchDistance(String source, String quoted) {
        if (!hasText(source) || !hasText(quoted)) {
            return Integer.MAX_VALUE;
        }
        int index = source.indexOf(quoted);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private boolean roleMatches(String actualRole, String expectedRole) {
        if (!hasText(actualRole) || !hasText(expectedRole)) {
            return false;
        }
        String[] aliases = actualRole.toLowerCase().split("\\|");
        for (String alias : aliases) {
            if (expectedRole.equals(alias.trim())) {
                return true;
            }
        }
        return false;
    }

    private int roleSpecificityScore(String actualRole, String expectedRole) {
        if (!hasText(actualRole) || !hasText(expectedRole)) {
            return Integer.MAX_VALUE;
        }
        String[] aliases = actualRole.toLowerCase().split("\\|");
        for (int i = 0; i < aliases.length; i++) {
            if (expectedRole.equals(aliases[i].trim())) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private int parseInteger(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception exception) {
            return 0;
        }
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
    }

    private String cleanText(String value) {
        return hasText(value) ? value.trim() : "";
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
                    .targetSlideId(intent.getTargetSlideId())
                    .insertAfterPageIndex(intent.getInsertAfterPageIndex())
                    .slideTitle(intent.getSlideTitle())
                    .slideBody(intent.getSlideBody())
                    .replacementText(intent.getReplacementText())
                    .anchorMode(intent.getAnchorMode())
                    .quotedText(intent.getQuotedText())
                    .elementRole(intent.getElementRole())
                    .expectedMatchCount(intent.getExpectedMatchCount())
                    .contentInstruction(intent.getContentInstruction())
                    .targetElementId(intent.getTargetElementId())
                    .targetBlockId(intent.getTargetBlockId())
                    .build());
        }
        return List.of();
    }

    private boolean requiresReplacement(PresentationEditOperation operation) {
        return operation.getActionType() == PresentationEditActionType.REPLACE_SLIDE_TITLE
                || operation.getActionType() == PresentationEditActionType.REPLACE_SLIDE_BODY;
    }

    private List<String> verificationTexts(PresentationEditOperation operation) {
        if (operation == null) {
            return List.of();
        }
        List<String> texts = new ArrayList<>();
        addVerificationText(texts, operation.getReplacementText());
        addVerificationText(texts, operation.getSlideTitle());
        if ((operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                || operation.getActionType() == PresentationEditActionType.MOVE_SLIDE)
                && hasText(operation.getSlideBody())) {
            splitBodyItems(operation.getSlideBody()).forEach(item -> addVerificationText(texts, item));
        } else {
            addVerificationText(texts, operation.getSlideBody());
        }
        return texts;
    }

    private void addVerificationText(List<String> texts, String value) {
        if (hasText(value) && !texts.contains(value.trim())) {
            texts.add(value.trim());
        }
    }

    private String buildSummarySegment(int pageIndex, PresentationEditOperation operation) {
        if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE) {
            String title = firstNonBlank(operation.getSlideTitle(), operation.getReplacementText(), "新增内容");
            if (pageIndex <= 0) {
                return "已在最前新增一页：" + title;
            }
            return "已在第 " + pageIndex + " 页后新增一页：" + title;
        }
        if (operation.getActionType() == PresentationEditActionType.DELETE_SLIDE) {
            return "已删除第 " + pageIndex + " 页";
        }
        if (operation.getActionType() == PresentationEditActionType.MOVE_SLIDE) {
            Integer target = operation.getInsertAfterPageIndex();
            if (target != null && target <= 0) {
                return "已移动第 " + pageIndex + " 页到最前";
            }
            return "已移动第 " + pageIndex + " 页到第 " + target + " 页后";
        }
        String label = operation.getTargetElementType() == PresentationTargetElementType.BODY ? "正文" : "标题";
        return "第 " + pageIndex + " 页" + label + "改为" + firstNonBlank(operation.getReplacementText(), "指定内容");
    }

    private String joinSummary(List<String> segments) {
        if (segments == null || segments.isEmpty()) {
            return "PPT 已更新";
        }
        if (segments.size() == 1) {
            if (segments.get(0).startsWith("已")) {
                return segments.get(0);
            }
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

    private String titleFromReplacement(PresentationEditOperation operation) {
        if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                && hasText(operation.getReplacementText())
                && !hasText(operation.getSlideBody())) {
            return operation.getReplacementText();
        }
        return null;
    }

    private String bodyFromReplacement(PresentationEditOperation operation) {
        if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE
                && hasText(operation.getReplacementText())
                && hasText(operation.getSlideTitle())) {
            return operation.getReplacementText();
        }
        return null;
    }

    private String textContent(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return hasText(text) ? text.trim() : null;
    }

    private boolean containsAny(String value, String... needles) {
        if (!hasText(value) || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (hasText(needle) && value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    private record SlideInfo(String slideId, int pageIndex, Element element) {
    }

    private record Deck(List<SlideInfo> slides) {
    }

    private record InsertPosition(String beforeSlideId, int templateIndex, int insertAfterPageIndex, String insertAfterLabel) {
    }

    private record OperationResult(List<String> modifiedSlideIds, List<String> verificationTexts, String summarySegment) {
    }
}
