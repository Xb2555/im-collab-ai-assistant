package com.lark.imcollab.harness.presentation.support;

import com.lark.imcollab.common.model.entity.PresentationLayoutSpec;
import com.lark.imcollab.common.model.entity.PresentationSnapshot;
import com.lark.imcollab.common.model.enums.PresentationEditability;
import com.lark.imcollab.common.model.enums.PresentationElementKind;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class SlideSnapshotBuilder {

    public List<PresentationSnapshot> build(Element slide, int pageIndex) {
        List<PresentationSnapshot> snapshots = new ArrayList<>();
        if (slide == null) {
            return snapshots;
        }
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
            String semanticRole = resolveTextSemanticRole(shape, targetType, bodyShapes);
            List<PresentationSnapshot> textSnapshots = buildTextSnapshots(
                    slide,
                    shape,
                    pageIndex,
                    i,
                    targetType,
                    semanticRole
            );
            if (textSnapshots.isEmpty()) {
                snapshots.add(baseShapeSnapshot(slide, shape, pageIndex, i, targetType, semanticRole));
            } else {
                snapshots.addAll(textSnapshots);
            }
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
                    .nodePath("slide/data/img[" + (i + 1) + "]")
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

    private List<PresentationSnapshot> buildTextSnapshots(
            Element slide,
            Element shape,
            int pageIndex,
            int shapeIndex,
            PresentationTargetElementType targetType,
            String semanticRole
    ) {
        List<PresentationSnapshot> snapshots = new ArrayList<>();
        NodeList contents = shape.getElementsByTagName("content");
        if (contents.getLength() == 0) {
            return snapshots;
        }
        for (int c = 0; c < contents.getLength(); c++) {
            Element content = (Element) contents.item(c);
            NodeList contentChildren = content.getChildNodes();
            int paragraphOrdinal = 0;
            int listOrdinal = 0;
            for (int i = 0; i < contentChildren.getLength(); i++) {
                Node child = contentChildren.item(i);
                if (!(child instanceof Element element)) {
                    continue;
                }
                if ("p".equalsIgnoreCase(element.getTagName())) {
                    paragraphOrdinal++;
                    String text = cleanText(element.getTextContent());
                    if (text.isBlank()) {
                        continue;
                    }
                    snapshots.add(PresentationSnapshot.builder()
                            .slideId(firstNonBlank(slide.getAttribute("id")))
                            .pageIndex(pageIndex)
                            .elementId(firstNonBlank(shape.getAttribute("id"), "shape-" + shapeIndex))
                            .blockId(firstNonBlank(shape.getAttribute("id"), "shape-" + shapeIndex))
                            .nodePath("slide/data/shape[" + (shapeIndex + 1) + "]/content[" + (c + 1) + "]/p[" + paragraphOrdinal + "]")
                            .elementKind(targetType == PresentationTargetElementType.TITLE ? PresentationElementKind.TITLE : PresentationElementKind.BODY)
                            .textType(targetType == PresentationTargetElementType.TITLE ? "title" : "body")
                            .textContent(text)
                            .normalizedText(normalizeText(text))
                            .paragraphIndex(paragraphOrdinal)
                            .boundingBox(buildBoundingBox(shape))
                            .semanticRole(semanticRole + "|paragraph-" + paragraphOrdinal)
                            .editability(PresentationEditability.NATIVE_EDITABLE)
                            .build());
                    continue;
                }
                if ("ul".equalsIgnoreCase(element.getTagName()) || "ol".equalsIgnoreCase(element.getTagName())) {
                    NodeList listChildren = element.getChildNodes();
                    int listItemOrdinal = 0;
                    for (int j = 0; j < listChildren.getLength(); j++) {
                        Node listChild = listChildren.item(j);
                        if (!(listChild instanceof Element listElement) || !"li".equalsIgnoreCase(listElement.getTagName())) {
                            continue;
                        }
                        listItemOrdinal++;
                        listOrdinal++;
                        String text = cleanText(listElement.getTextContent());
                        if (text.isBlank()) {
                            continue;
                        }
                        snapshots.add(PresentationSnapshot.builder()
                                .slideId(firstNonBlank(slide.getAttribute("id")))
                                .pageIndex(pageIndex)
                                .elementId(firstNonBlank(shape.getAttribute("id"), "shape-" + shapeIndex))
                                .blockId(firstNonBlank(shape.getAttribute("id"), "shape-" + shapeIndex))
                                .nodePath("slide/data/shape[" + (shapeIndex + 1) + "]/content[" + (c + 1) + "]/"
                                        + element.getTagName().toLowerCase() + "[1]/li[" + listItemOrdinal + "]")
                                .elementKind(targetType == PresentationTargetElementType.TITLE ? PresentationElementKind.TITLE : PresentationElementKind.BODY)
                                .textType(targetType == PresentationTargetElementType.TITLE ? "title" : "body")
                                .textContent(text)
                                .normalizedText(normalizeText(text))
                                .paragraphIndex(++paragraphOrdinal)
                                .listItemIndex(listOrdinal)
                                .boundingBox(buildBoundingBox(shape))
                                .semanticRole(semanticRole + "|list-item-" + listOrdinal)
                                .editability(PresentationEditability.NATIVE_EDITABLE)
                                .build());
                    }
                }
            }
        }
        return snapshots;
    }

    private PresentationSnapshot baseShapeSnapshot(
            Element slide,
            Element shape,
            int pageIndex,
            int shapeIndex,
            PresentationTargetElementType targetType,
            String semanticRole
    ) {
        String textContent = cleanText(shape.getTextContent());
        return PresentationSnapshot.builder()
                .slideId(firstNonBlank(slide.getAttribute("id")))
                .pageIndex(pageIndex)
                .elementId(firstNonBlank(shape.getAttribute("id"), "shape-" + shapeIndex))
                .blockId(firstNonBlank(shape.getAttribute("id"), "shape-" + shapeIndex))
                .nodePath("slide/data/shape[" + (shapeIndex + 1) + "]")
                .elementKind(targetType == PresentationTargetElementType.TITLE ? PresentationElementKind.TITLE : PresentationElementKind.BODY)
                .textType(targetType == PresentationTargetElementType.TITLE ? "title" : "body")
                .textContent(textContent)
                .normalizedText(normalizeText(textContent))
                .boundingBox(buildBoundingBox(shape))
                .semanticRole(semanticRole)
                .editability(PresentationEditability.NATIVE_EDITABLE)
                .build();
    }

    private PresentationLayoutSpec buildBoundingBox(Element element) {
        return PresentationLayoutSpec.builder()
                .topLeftX(parseInteger(element.getAttribute("topLeftX")))
                .topLeftY(parseInteger(element.getAttribute("topLeftY")))
                .width(parseInteger(element.getAttribute("width")))
                .height(parseInteger(element.getAttribute("height")))
                .build();
    }

    private PresentationTargetElementType detectTargetType(Element shape) {
        NodeList contents = shape.getElementsByTagName("content");
        for (int i = 0; i < contents.getLength(); i++) {
            Element content = (Element) contents.item(i);
            if ("title".equalsIgnoreCase(content.getAttribute("textType"))) {
                return PresentationTargetElementType.TITLE;
            }
        }
        return PresentationTargetElementType.BODY;
    }

    private String resolveTextSemanticRole(
            Element shape,
            PresentationTargetElementType targetType,
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
        return value == null ? "" : value.trim();
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
}
