package com.lark.imcollab.harness.presentation.support;

import com.lark.imcollab.common.model.entity.PresentationEditOperation;
import com.lark.imcollab.common.model.enums.PresentationEditActionType;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class PresentationIntentGuard {

    private static final Set<PresentationEditActionType> DELETE_ACTIONS = EnumSet.of(
            PresentationEditActionType.DELETE_ELEMENT,
            PresentationEditActionType.DELETE_SLIDE
    );
    private static final Set<PresentationEditActionType> INSERT_ACTIONS = EnumSet.of(
            PresentationEditActionType.INSERT_AFTER_ELEMENT,
            PresentationEditActionType.INSERT_SLIDE
    );
    private static final Set<PresentationEditActionType> REWRITE_ACTIONS = EnumSet.of(
            PresentationEditActionType.REWRITE_ELEMENT,
            PresentationEditActionType.EXPAND_ELEMENT,
            PresentationEditActionType.SHORTEN_ELEMENT,
            PresentationEditActionType.REPLACE_ELEMENT,
            PresentationEditActionType.REPLACE_SLIDE_TITLE,
            PresentationEditActionType.REPLACE_SLIDE_BODY,
            PresentationEditActionType.REPLACE_IMAGE,
            PresentationEditActionType.REPLACE_CHART
    );

    public void validate(String instruction, List<PresentationEditOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return;
        }
        UserActionSemantic semantic = resolveSemantic(instruction);
        if (semantic == UserActionSemantic.UNKNOWN) {
            return;
        }
        for (PresentationEditOperation operation : operations) {
            if (operation == null || operation.getActionType() == null) {
                continue;
            }
            if (!semantic.supports(operation.getActionType())) {
                throw new IllegalArgumentException("PPT 修改请求在写入前已拒绝：用户要求是"
                        + semantic.label + "，但解析结果变成了 " + operation.getActionType().name());
            }
        }
    }

    private UserActionSemantic resolveSemantic(String instruction) {
        String normalized = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "删了", "删除", "去掉", "移除")) {
            return UserActionSemantic.DELETE;
        }
        if (containsAny(normalized, "插入", "补充", "加上", "增加")) {
            return UserActionSemantic.INSERT;
        }
        if (containsAny(normalized, "改成", "替换", "写详细", "精简")) {
            return UserActionSemantic.REWRITE;
        }
        return UserActionSemantic.UNKNOWN;
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank() || needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private enum UserActionSemantic {
        DELETE("删除") {
            @Override
            boolean supports(PresentationEditActionType actionType) {
                return DELETE_ACTIONS.contains(actionType);
            }
        },
        INSERT("插入") {
            @Override
            boolean supports(PresentationEditActionType actionType) {
                return INSERT_ACTIONS.contains(actionType);
            }
        },
        REWRITE("改写") {
            @Override
            boolean supports(PresentationEditActionType actionType) {
                return REWRITE_ACTIONS.contains(actionType);
            }
        },
        UNKNOWN("未知") {
            @Override
            boolean supports(PresentationEditActionType actionType) {
                return true;
            }
        };

        private final String label;

        UserActionSemantic(String label) {
            this.label = label;
        }

        abstract boolean supports(PresentationEditActionType actionType);
    }
}
