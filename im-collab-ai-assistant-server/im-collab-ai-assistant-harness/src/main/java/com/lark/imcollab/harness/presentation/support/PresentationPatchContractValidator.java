package com.lark.imcollab.harness.presentation.support;

import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.entity.PresentationSnapshot;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import com.lark.imcollab.common.model.enums.PresentationTargetElementType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class PresentationPatchContractValidator {

    public void validate(
            String instruction,
            PresentationEditOperation operation,
            PresentationSnapshot targetSnapshot,
            String replacementXml,
            List<Map<String, Object>> parts,
            boolean wholeSlideReplacement
    ) {
        if (operation == null || operation.getActionType() == null) {
            return;
        }
        switch (operation.getActionType()) {
            case DELETE_SLIDE -> validateDeleteSlide(parts, wholeSlideReplacement);
            case DELETE_ELEMENT -> validateDeleteElement(operation, targetSnapshot, replacementXml, parts, wholeSlideReplacement);
            case INSERT_AFTER_ELEMENT -> validateInsert(operation, replacementXml, parts, wholeSlideReplacement);
            case REWRITE_ELEMENT, EXPAND_ELEMENT, SHORTEN_ELEMENT, REPLACE_ELEMENT,
                    REPLACE_SLIDE_TITLE, REPLACE_SLIDE_BODY, REPLACE_IMAGE, REPLACE_CHART ->
                    validateRewrite(operation, targetSnapshot, replacementXml, parts, wholeSlideReplacement);
            default -> {
            }
        }
    }

    private void validateDeleteSlide(List<Map<String, Object>> parts, boolean wholeSlideReplacement) {
        if ((parts != null && !parts.isEmpty()) || wholeSlideReplacement) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：整页删除不能混入页内 patch");
        }
    }

    private void validateDeleteElement(
            PresentationEditOperation operation,
            PresentationSnapshot targetSnapshot,
            String replacementXml,
            List<Map<String, Object>> parts,
            boolean wholeSlideReplacement
    ) {
        if (wholeSlideReplacement) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：删除元素不能通过整页正文重写落盘");
        }
        if (parts == null || parts.size() != 1 || replacementXml == null || replacementXml.isBlank()) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：删除元素 patch 不完整");
        }
        PresentationTargetElementType targetType = operation.getTargetElementType();
        if (!isSupportedDeleteTarget(targetType)) {
            throw new IllegalArgumentException("当前仅支持文本和图片元素删除");
        }
        if (targetType == PresentationTargetElementType.BODY) {
            if (targetSnapshot == null || targetSnapshot.getNodePath() == null) {
                if (!containsTag(replacementXml, "<shape")) {
                    throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：正文删除必须命中具体段落或单一文本框");
                }
            } else if (!containsTag(replacementXml, "<shape")) {
                throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：正文删除 replacement 必须回写目标文本框");
            }
            return;
        }
        if (targetType == PresentationTargetElementType.TITLE && !containsTag(replacementXml, "<deleted-shape")) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：标题删除必须走整元素删除标记");
        }
        if (targetType == PresentationTargetElementType.IMAGE && !containsTag(replacementXml, "<deleted-image")) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：图片删除必须走整元素删除标记");
        }
    }

    private void validateInsert(
            PresentationEditOperation operation,
            String replacementXml,
            List<Map<String, Object>> parts,
            boolean wholeSlideReplacement
    ) {
        if (parts == null || parts.size() != 1) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：插入内容 patch 不完整");
        }
        if (operation.getTargetElementType() == PresentationTargetElementType.BODY) {
            if (wholeSlideReplacement) {
                if (!containsAnyTag(replacementXml, "<slide", "<shape")) {
                    throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：插入内容不能生成无关整页改写");
                }
            } else if (!containsTag(replacementXml, "<shape")) {
                throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：插入内容必须落在目标文本框");
            }
        }
    }

    private void validateRewrite(
            PresentationEditOperation operation,
            PresentationSnapshot targetSnapshot,
            String replacementXml,
            List<Map<String, Object>> parts,
            boolean wholeSlideReplacement
    ) {
        if (parts == null || parts.size() != 1) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：改写类 patch 不完整");
        }
        if (wholeSlideReplacement) {
            throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：改写类操作不能通过整页 patch 落盘");
        }
        if (operation.getTargetElementType() == PresentationTargetElementType.IMAGE) {
            if (!containsTag(replacementXml, "<img")) {
                throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：图片替换必须回写图片元素");
            }
            return;
        }
        if (targetSnapshot != null && targetSnapshot.getNodePath() != null
                && (targetSnapshot.getNodePath().contains("/p[") || targetSnapshot.getNodePath().contains("/li["))) {
            if (!containsTag(replacementXml, "<shape")) {
                throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：段落改写必须只回写命中的文本框");
            }
        }
    }

    private boolean isSupportedDeleteTarget(PresentationTargetElementType targetType) {
        return targetType == PresentationTargetElementType.BODY
                || targetType == PresentationTargetElementType.TITLE
                || targetType == PresentationTargetElementType.IMAGE;
    }

    private boolean containsTag(String xml, String token) {
        return xml != null && token != null && xml.contains(token);
    }

    private boolean containsAnyTag(String xml, String... tokens) {
        if (xml == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && xml.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
