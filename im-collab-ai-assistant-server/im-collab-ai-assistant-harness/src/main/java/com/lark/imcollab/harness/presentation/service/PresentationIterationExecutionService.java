package com.lark.imcollab.harness.presentation.service;

import com.lark.imcollab.common.facade.PresentationEditIntentFacade;
import com.lark.imcollab.common.facade.PresentationIterationFacade;
import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.entity.PresentationEditIntent;
import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.PresentationSnapshot;
import com.lark.imcollab.common.model.enums.PresentationAnchorMode;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import com.lark.imcollab.common.model.vo.PresentationIterationVO;
import com.lark.imcollab.harness.presentation.support.PresentationAnchorResolver;
import com.lark.imcollab.harness.presentation.support.PresentationTargetStateVerifier;
import com.lark.imcollab.harness.presentation.support.SlideSnapshotBuilder;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class PresentationIterationExecutionService implements PresentationIterationFacade {

    private final LarkSlidesTool larkSlidesTool;
    private final PresentationEditIntentFacade intentFacade;
    private final PresentationBodyRewriteService bodyRewriteService;
    private final SlideSnapshotBuilder slideSnapshotBuilder;
    private final PresentationAnchorResolver anchorResolver;
    private final PresentationTargetStateVerifier targetStateVerifier;

    @Autowired
    public PresentationIterationExecutionService(
            LarkSlidesTool larkSlidesTool,
            PresentationEditIntentFacade intentFacade,
            PresentationBodyRewriteService bodyRewriteService,
            SlideSnapshotBuilder slideSnapshotBuilder,
            PresentationAnchorResolver anchorResolver,
            PresentationTargetStateVerifier targetStateVerifier
    ) {
        this.larkSlidesTool = larkSlidesTool;
        this.intentFacade = intentFacade;
        this.bodyRewriteService = bodyRewriteService;
        this.slideSnapshotBuilder = slideSnapshotBuilder;
        this.anchorResolver = anchorResolver;
        this.targetStateVerifier = targetStateVerifier;
    }

    public PresentationIterationExecutionService(
            LarkSlidesTool larkSlidesTool,
            PresentationEditIntentFacade intentFacade
    ) {
        this(larkSlidesTool, intentFacade, null, new SlideSnapshotBuilder(), new PresentationAnchorResolver(), new PresentationTargetStateVerifier());
    }

    public PresentationIterationExecutionService(
            LarkSlidesTool larkSlidesTool,
            PresentationEditIntentFacade intentFacade,
            PresentationBodyRewriteService bodyRewriteService
    ) {
        this(larkSlidesTool, intentFacade, bodyRewriteService, new SlideSnapshotBuilder(), new PresentationAnchorResolver(), new PresentationTargetStateVerifier());
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
        List<PresentationEditOperation> operations = resolveOperations(intent);
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("请明确要修改哪些页面，以及每页要改成什么内容");
        }
        LinkedHashSet<String> modifiedSlideIds = new LinkedHashSet<>();
        List<String> summarySegments = new ArrayList<>();
        for (PresentationEditOperation operation : operations) {
            LarkSlidesFetchResult beforePresentation = larkSlidesTool.fetchPresentation(presentation);
            OperationResult operationResult = executeOperation(presentation, beforePresentation.getXml(), operation);
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

    private OperationResult executeOperation(String presentationId, String presentationXml, PresentationEditOperation operation) {
        if (operation.getActionType() == PresentationEditActionType.INSERT_SLIDE) {
            return insertSlide(presentationId, presentationXml, operation);
        }
        if (operation.getActionType() == PresentationEditActionType.DELETE_SLIDE) {
            return deleteSlide(presentationId, presentationXml, operation);
        }
        if (operation.getActionType() == PresentationEditActionType.MOVE_SLIDE) {
            return moveSlide(presentationId, presentationXml, operation);
        }
        Deck deck = parseDeck(presentationXml);
        SlideRef slideRef = resolveTargetSlide(deck, operation);
        String slideXml = fetchSlideXmlOrFallback(presentationId, slideRef);
        Document slideDocument = parseXml(slideXml);
        Element slide = findSlideElement(slideDocument);
        List<PresentationSnapshot> beforeSnapshots = slideSnapshotBuilder.build(slide, slideRef.pageIndex());
        PresentationSnapshot targetSnapshot = anchorResolver.resolve(beforeSnapshots, operation);
        if (targetSnapshot == null && operation.getAnchorMode() != null && operation.getAnchorMode() != PresentationAnchorMode.BY_PAGE_INDEX) {
            throw new IllegalArgumentException("无法唯一定位到要修改的 PPT 元素，请补充更具体的页内锚点");
        }
        Element targetElement = resolveTargetElement(slide, targetSnapshot, operation);
        if (targetElement == null) {
            throw new IllegalArgumentException("无法定位到要修改的 PPT 元素，请补充更具体的修改位置");
        }
        SlideTarget target = new SlideTarget(slideRef.slideId(), slideRef.pageIndex(), targetSnapshot, targetElement, slide);
        PresentationEditOperation executableOperation = materializeOperation(target, operation);
        if (executableOperation.getActionType() == PresentationEditActionType.INSERT_AFTER_ELEMENT) {
            String updatedSlideXml = buildSlideXmlWithInsertedContent(target, executableOperation);
            larkSlidesTool.replaceWholeSlide(presentationId, target.slideId(), updatedSlideXml);
            verifyAfterReplaceWholeSlide(presentationId, target, executableOperation);
            return new OperationResult(List.of(target.slideId()), buildSummarySegment(target.pageIndex(), executableOperation));
        }
        Map<String, Object> part = buildPart(target, executableOperation);
        larkSlidesTool.replaceSlide(presentationId, target.slideId(), List.of(part));
        verifyAfterReplaceSlide(presentationId, target, executableOperation);
        return new OperationResult(List.of(target.slideId()), buildSummarySegment(target.pageIndex(), executableOperation));
    }

    private void verifyAfterReplaceSlide(String presentationId, SlideTarget target, PresentationEditOperation operation) {
        String slideXml = fetchUpdatedSlideXml(presentationId, target);
        Element slide = findSlideElement(parseXml(slideXml));
        List<PresentationSnapshot> afterSnapshots = slideSnapshotBuilder.build(slide, target.pageIndex());
        targetStateVerifier.verify(target.snapshot(), operation, afterSnapshots);
    }

    private void verifyAfterReplaceWholeSlide(String presentationId, SlideTarget target, PresentationEditOperation operation) {
        String slideXml = fetchUpdatedSlideXml(presentationId, target);
        Element slide = findSlideElement(parseXml(slideXml));
        List<PresentationSnapshot> afterSnapshots = slideSnapshotBuilder.build(slide, target.pageIndex());
        if (operation.getActionType() == PresentationEditActionType.INSERT_AFTER_ELEMENT) {
            String expected = firstNonBlank(operation.getReplacementText());
            boolean matched = afterSnapshots.stream().anyMatch(snapshot -> snapshot.getTextContent() != null && snapshot.getTextContent().contains(expected));
            if (!matched) {
                throw new IllegalStateException("PPT update verification failed: inserted content not found on target slide");
            }
            return;
        }
        targetStateVerifier.verify(target.snapshot(), operation, afterSnapshots);
    }

    private SlideRef resolveTargetSlide(Deck deck, PresentationEditOperation operation) {
        if (deck == null || deck.slides().isEmpty()) {
            throw new IllegalStateException("No slides found in presentation XML");
        }
        if (operation.getPageIndex() != null) {
            if (operation.getPageIndex() < 1 || operation.getPageIndex() > deck.slides().size()) {
                throw new IllegalArgumentException("PPT 中不存在第 " + operation.getPageIndex() + " 页");
            }
            return deck.slides().get(operation.getPageIndex() - 1);
        }
        if (hasText(operation.getTargetSlideId())) {
            return deck.slides().stream()
                    .filter(slide -> operation.getTargetSlideId().equals(slide.slideId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("无法根据 slideId 定位目标页"));
        }
        if (hasText(operation.getTargetPageTitle())) {
            List<SlideRef> matches = deck.slides().stream()
                    .filter(slide -> slide.title() != null && slide.title().contains(operation.getTargetPageTitle().trim()))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("页标题命中不唯一，请补充页码");
            }
        }
        if (operation.getAnchorMode() == PresentationAnchorMode.BY_QUOTED_TEXT && hasText(operation.getQuotedText())) {
            List<SlideRef> matches = deck.slides().stream()
                    .filter(slide -> slide.xml() != null && normalizeText(slide.xml()).contains(normalizeText(operation.getQuotedText())))
                    .toList();
            if (matches.size() == 1) {
                return matches.get(0);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("引用文本在多页中重复出现，请补充页码后再修改");
            }
        }
        throw new IllegalArgumentException("请明确要改第几页");
    }

    private OperationResult insertSlide(String presentationId, String presentationXml, PresentationEditOperation operation) {
        Deck deck = parseDeck(presentationXml);
        InsertPosition position = resolveInsertPosition(deck, insertAfterPageIndex(operation));
        SlideRef template = chooseTemplateSlide(deck, position.templateIndex());
        String templateXml = fetchSlideXmlOrFallback(presentationId, template);
        String slideXml = buildClonedSlideXml(templateXml, operation);
        LarkSlidesReplaceResult result = larkSlidesTool.createSlide(presentationId, slideXml, position.beforeSlideId());
        return new OperationResult(
                List.of(firstNonBlank(result.getSlideId(), "inserted-after-" + position.insertAfterLabel())),
                buildSummarySegment(position.insertAfterPageIndex(), operation)
        );
    }

    private OperationResult deleteSlide(String presentationId, String presentationXml, PresentationEditOperation operation) {
        Deck deck = parseDeck(presentationXml);
        if (deck.slides().size() <= 1) {
            throw new IllegalArgumentException("不能删除唯一一页 PPT");
        }
        SlideRef target = slideAtPage(deck, operation.getPageIndex());
        larkSlidesTool.deleteSlide(presentationId, target.slideId());
        return new OperationResult(List.of(target.slideId()), buildSummarySegment(target.pageIndex(), operation));
    }

    private OperationResult moveSlide(String presentationId, String presentationXml, PresentationEditOperation operation) {
        Deck deck = parseDeck(presentationXml);
        SlideRef source = slideAtPage(deck, operation.getPageIndex());
        if (operation.getInsertAfterPageIndex() == null) {
            throw new IllegalArgumentException("请明确要把该页移动到第几页之后");
        }
        int insertAfterPageIndex = normalizeMoveInsertAfterPageIndex(deck, operation.getInsertAfterPageIndex());
        String sourceXml = fetchSlideXmlOrFallback(presentationId, source);
        String slideXml = buildClonedSlideXml(sourceXml, operation);
        String beforeSlideId = resolveMoveBeforeSlideId(deck, insertAfterPageIndex);
        LarkSlidesReplaceResult created = larkSlidesTool.createSlide(presentationId, slideXml, beforeSlideId);
        larkSlidesTool.deleteSlide(presentationId, source.slideId());
        PresentationEditOperation summaryOperation = PresentationEditOperation.builder()
                .actionType(operation.getActionType())
                .targetElementType(operation.getTargetElementType())
                .pageIndex(operation.getPageIndex())
                .insertAfterPageIndex(insertAfterPageIndex)
                .slideTitle(operation.getSlideTitle())
                .slideBody(operation.getSlideBody())
                .replacementText(operation.getReplacementText())
                .build();
        return new OperationResult(List.of(firstNonBlank(created.getSlideId(), source.slideId())), buildSummarySegment(source.pageIndex(), summaryOperation));
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

    private int normalizeMoveInsertAfterPageIndex(Deck deck, int insertAfterPageIndex) {
        if (insertAfterPageIndex < 0) {
            return deck.slides().size();
        }
        return insertAfterPageIndex;
    }

    private SlideRef slideAtPage(Deck deck, Integer pageIndex) {
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

    private SlideRef chooseTemplateSlide(Deck deck, int preferredIndex) {
        int normalizedIndex = Math.min(Math.max(preferredIndex, 0), deck.slides().size() - 1);
        SlideRef preferred = deck.slides().get(normalizedIndex);
        if (isReusableBodyTemplate(preferred)) {
            return preferred;
        }
        SlideRef closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (SlideRef slide : deck.slides()) {
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

    private boolean isReusableBodyTemplate(SlideRef slide) {
        String title = slide.title();
        if (containsAny(title, "封面", "目录", "总结", "结语", "致谢", "谢谢", "Q&A")) {
            return false;
        }
        return findBodyReplacementShape(slide.element()) != null;
    }

    private Deck parseDeck(String xml) {
        Document document = parseXml(xml);
        NodeList nodes = document.getElementsByTagName("slide");
        if (nodes.getLength() == 0 && "slide".equalsIgnoreCase(document.getDocumentElement().getTagName())) {
            nodes = document.getChildNodes();
        }
        List<SlideRef> slides = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element slide) || !"slide".equalsIgnoreCase(slide.getTagName())) {
                continue;
            }
            String slideId = firstNonBlank(slide.getAttribute("id"), slide.getAttribute("slide_id"), slide.getAttribute("slideId"));
            requireValue(slideId, "slide id");
            slides.add(new SlideRef(slideId, slides.size() + 1, slide, serializeElement(slide), textContent(findTargetShape(slide, PresentationTargetElementType.TITLE))));
        }
        if (slides.isEmpty()) {
            throw new IllegalStateException("No slides found in presentation XML");
        }
        return new Deck(slides);
    }

    private String fetchSlideXmlOrFallback(String presentationId, SlideRef slideRef) {
        try {
            LarkSlidesFetchResult fetched = larkSlidesTool.fetchSlide(presentationId, slideRef.slideId());
            if (fetched != null && hasText(fetched.getXml())) {
                return fetched.getXml();
            }
        } catch (RuntimeException ignored) {
        }
        return firstNonBlank(slideRef.xml(), serializeElement(slideRef.element()));
    }

    private String fetchUpdatedSlideXml(String presentationId, SlideTarget target) {
        try {
            LarkSlidesFetchResult fetched = larkSlidesTool.fetchSlide(presentationId, target.slideId());
            if (fetched != null && hasText(fetched.getXml())) {
                return fetched.getXml();
            }
        } catch (RuntimeException ignored) {
        }
        LarkSlidesFetchResult presentation = larkSlidesTool.fetchPresentation(presentationId);
        Deck deck = parseDeck(presentation.getXml());
        return deck.slides().stream()
                .filter(slide -> target.slideId().equals(slide.slideId()))
                .map(SlideRef::xml)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("PPT update verification failed: target slide not found after edit"));
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

    private PresentationEditOperation materializeOperation(SlideTarget target, PresentationEditOperation operation) {
        if (!needsGeneratedReplacement(operation)) {
            return operation;
        }
        if (bodyRewriteService == null) {
            throw new IllegalStateException("PPT 正文改写生成器未配置，无法自动改写正文");
        }
        String sourceText = target.snapshot() != null ? target.snapshot().getTextContent() : textContent(target.element());
        String generated = operation.getActionType() == PresentationEditActionType.INSERT_AFTER_ELEMENT
                ? bodyRewriteService.generateInsertion(sourceText, operation)
                : bodyRewriteService.rewrite(sourceText, operation);
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
                .targetPageTitle(operation.getTargetPageTitle())
                .targetParagraphIndex(operation.getTargetParagraphIndex())
                .targetListItemIndex(operation.getTargetListItemIndex())
                .targetNodePath(operation.getTargetNodePath())
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
                || operation.getActionType() == PresentationEditActionType.SHORTEN_ELEMENT
                || operation.getActionType() == PresentationEditActionType.INSERT_AFTER_ELEMENT;
    }

    private Map<String, Object> buildPart(SlideTarget target, PresentationEditOperation operation) {
        if (operation.getActionType() == PresentationEditActionType.REPLACE_IMAGE
                || operation.getTargetElementType() == PresentationTargetElementType.IMAGE) {
            return replaceImagePart(target, operation);
        }
        String newText = firstNonBlank(operation.getReplacementText());
        String textType = operation.getTargetElementType() == PresentationTargetElementType.BODY ? "body" : "title";
        if (target.snapshot() != null && target.snapshot().getNodePath() != null
                && (target.snapshot().getParagraphIndex() != null || target.snapshot().getListItemIndex() != null)) {
            Element content = findContent(target.element(), operation.getTargetElementType());
            if (content != null) {
                replaceTextNode(content, target.snapshot(), newText);
                return replacePart(target.blockId(), serializeElement(target.element()));
            }
        }
        return replacePart(target.blockId(), buildTextShape(newText, target.element(), textType));
    }

    private void replaceTextNode(Element content, PresentationSnapshot snapshot, String newText) {
        if (snapshot.getNodePath() != null && snapshot.getNodePath().contains("/li[")) {
            NodeList listNodes = content.getElementsByTagName("li");
            int targetIndex = snapshot.getListItemIndex() == null ? -1 : snapshot.getListItemIndex() - 1;
            if (targetIndex >= 0 && targetIndex < listNodes.getLength()) {
                Element li = (Element) listNodes.item(targetIndex);
                replaceChildrenWithParagraph(li, newText);
            }
            return;
        }
        NodeList paragraphNodes = content.getElementsByTagName("p");
        int targetIndex = snapshot.getParagraphIndex() == null ? -1 : snapshot.getParagraphIndex() - 1;
        if (targetIndex >= 0 && targetIndex < paragraphNodes.getLength()) {
            Element paragraph = (Element) paragraphNodes.item(targetIndex);
            removeAllChildren(paragraph);
            paragraph.appendChild(paragraph.getOwnerDocument().createTextNode(newText));
        }
    }

    private void replaceChildrenWithParagraph(Element li, String newText) {
        removeAllChildren(li);
        Element p = li.getOwnerDocument().createElement("p");
        p.appendChild(li.getOwnerDocument().createTextNode(newText));
        li.appendChild(p);
    }

    private void removeAllChildren(Element element) {
        while (element.hasChildNodes()) {
            element.removeChild(element.getFirstChild());
        }
    }

    private Map<String, Object> replaceImagePart(SlideTarget target, PresentationEditOperation operation) {
        String replacement = firstNonBlank(operation.getReplacementText(), operation.getContentInstruction(), "替换图片");
        String token = replacement.startsWith("boxcn") ? replacement : "boxcn-replaced-image";
        return Map.of(
                "action", "block_replace",
                "block_id", firstNonBlank(target.blockId(), "image-block"),
                "replacement", """
                        <img src="%s" topLeftX="560" topLeftY="90" width="320" height="180" alt="%s"/>
                        """.formatted(token, escapeXml(replacement)).trim()
        );
    }

    private Map<String, Object> replacePart(String blockId, String replacementXml) {
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("action", "block_replace");
        part.put("block_id", blockId);
        part.put("replacement", replacementXml);
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

    private String buildSlideXmlWithInsertedContent(SlideTarget target, PresentationEditOperation operation) {
        if (target.snapshot() != null && target.snapshot().getNodePath() != null && target.snapshot().getNodePath().contains("/li[")) {
            Element content = findContent(target.element(), operation.getTargetElementType());
            NodeList listNodes = content == null ? null : content.getElementsByTagName("li");
            int targetIndex = target.snapshot().getListItemIndex() == null ? -1 : target.snapshot().getListItemIndex() - 1;
            if (listNodes != null && targetIndex >= 0 && targetIndex < listNodes.getLength()) {
                Element targetLi = (Element) listNodes.item(targetIndex);
                Element insertedLi = targetLi.getOwnerDocument().createElement("li");
                Element p = targetLi.getOwnerDocument().createElement("p");
                p.appendChild(targetLi.getOwnerDocument().createTextNode(firstNonBlank(operation.getReplacementText())));
                insertedLi.appendChild(p);
                Node next = targetLi.getNextSibling();
                if (next == null) {
                    targetLi.getParentNode().appendChild(insertedLi);
                } else {
                    targetLi.getParentNode().insertBefore(insertedLi, next);
                }
                return serializeElement(target.slide());
            }
        }
        if (target.snapshot() != null && target.snapshot().getNodePath() != null && target.snapshot().getNodePath().contains("/p[")) {
            Element content = findContent(target.element(), operation.getTargetElementType());
            NodeList paragraphs = content == null ? null : content.getElementsByTagName("p");
            int targetIndex = target.snapshot().getParagraphIndex() == null ? -1 : target.snapshot().getParagraphIndex() - 1;
            if (paragraphs != null && targetIndex >= 0 && targetIndex < paragraphs.getLength()) {
                Element targetParagraph = (Element) paragraphs.item(targetIndex);
                Element insertedParagraph = targetParagraph.getOwnerDocument().createElement("p");
                insertedParagraph.appendChild(targetParagraph.getOwnerDocument().createTextNode(firstNonBlank(operation.getReplacementText())));
                Node next = targetParagraph.getNextSibling();
                if (next == null) {
                    targetParagraph.getParentNode().appendChild(insertedParagraph);
                } else {
                    targetParagraph.getParentNode().insertBefore(insertedParagraph, next);
                }
                return serializeElement(target.slide());
            }
        }
        Element originalShape = target.element();
        Element inserted = parseXml(buildInsertedTextShape(target, firstNonBlank(operation.getReplacementText()), operation.getTargetElementType())).getDocumentElement();
        Element imported = (Element) originalShape.getOwnerDocument().importNode(inserted, true);
        Node next = originalShape.getNextSibling();
        if (next == null) {
            originalShape.getParentNode().appendChild(imported);
        } else {
            originalShape.getParentNode().insertBefore(imported, next);
        }
        return serializeElement(target.slide());
    }

    private String buildInsertedTextShape(SlideTarget target, String newText, PresentationTargetElementType targetType) {
        Element originalShape = target.element();
        String x = firstNonBlank(attribute(originalShape, "topLeftX"), "80");
        String y = offset(attribute(originalShape, "topLeftY"), attribute(originalShape, "height"), 20, "420");
        String width = firstNonBlank(attribute(originalShape, "width"), "800");
        String height = "80";
        String textType = targetType == PresentationTargetElementType.TITLE ? "title" : "body";
        return """
                <shape type="text" topLeftX="%s" topLeftY="%s" width="%s" height="%s">
                  <content textType="%s"><p>%s</p></content>
                </shape>
                """.formatted(x, y, width, height, textType, escapeXml(newText)).trim();
    }

    private Element resolveTargetElement(Element slide, PresentationSnapshot snapshot, PresentationEditOperation operation) {
        if (snapshot != null) {
            Element byBlock = findShapeById(slide, snapshot.getBlockId());
            if (byBlock != null) {
                return byBlock;
            }
        }
        return findTargetShape(slide, operation.getTargetElementType());
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
                if ("title".equalsIgnoreCase(content.getAttribute("textType")) && targetElementType == PresentationTargetElementType.TITLE) {
                    return shape;
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
        removeAllChildren(content);
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

    private List<PresentationEditOperation> resolveOperations(PresentationEditIntent intent) {
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
                    .targetPageTitle(intent.getTargetPageTitle())
                    .targetParagraphIndex(intent.getTargetParagraphIndex())
                    .targetListItemIndex(intent.getTargetListItemIndex())
                    .targetNodePath(intent.getTargetNodePath())
                    .build());
        }
        return List.of();
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
        if (operation.getActionType() == PresentationEditActionType.INSERT_AFTER_ELEMENT) {
            return "已在第 " + pageIndex + " 页目标段落后插入内容";
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

    private String attribute(Element element, String name) {
        if (element == null || !element.hasAttribute(name)) {
            return null;
        }
        String value = element.getAttribute(name);
        return value == null || value.isBlank() ? null : value;
    }

    private String textContent(Element element) {
        if (element == null) {
            return null;
        }
        String text = element.getTextContent();
        return hasText(text) ? text.trim() : null;
    }

    private String escapeXml(String value) {
        return value == null ? "" : value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
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

    private String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "");
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

    private record SlideTarget(String slideId, int pageIndex, PresentationSnapshot snapshot, Element element, Element slide) {
        private String blockId() {
            if (snapshot != null && snapshot.getBlockId() != null) {
                return snapshot.getBlockId();
            }
            return element == null ? null : first(element, "id");
        }

        private static String first(Element element, String attributeName) {
            if (element == null || !element.hasAttribute(attributeName)) {
                return null;
            }
            String value = element.getAttribute(attributeName);
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    private record SlideRef(String slideId, int pageIndex, Element element, String xml, String title) {
    }

    private record Deck(List<SlideRef> slides) {
    }

    private record InsertPosition(String beforeSlideId, int templateIndex, int insertAfterPageIndex, String insertAfterLabel) {
    }

    private record OperationResult(List<String> modifiedSlideIds, String summarySegment) {
    }
}
