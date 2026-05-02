package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.ContextAcquisitionPlan;
import com.lark.imcollab.common.model.entity.ContextAcquisitionResult;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ContextAcquisitionNodeService {

    private final PlannerContextAcquisitionTool acquisitionTool;

    public ContextAcquisitionNodeService(PlannerContextAcquisitionTool acquisitionTool) {
        this.acquisitionTool = acquisitionTool;
    }

    public ContextCollectionOutcome collect(
            String taskId,
            String rawInstruction,
            WorkspaceContext workspaceContext,
            ContextAcquisitionPlan plan
    ) {
        if (plan == null || !plan.isNeedCollection()) {
            return new ContextCollectionOutcome(
                    ContextSufficiencyResult.insufficient(
                            List.of("source_context"),
                            "我还需要你提供要整理的聊天内容或文档链接。",
                            "missing context acquisition plan"
                    ),
                    workspaceContext
            );
        }
        ContextAcquisitionResult result = acquisitionTool.acquireContext(taskId, rawInstruction, workspaceContext, plan);
        if (result != null && result.isSuccess() && result.isSufficient()) {
            WorkspaceContext merged = acquisitionTool.mergeWorkspaceContext(workspaceContext, result);
            return new ContextCollectionOutcome(
                    ContextSufficiencyResult.sufficient(result.getContextSummary(), "context collected"),
                    merged
            );
        }
        String question = result == null || result.getMessage() == null || result.getMessage().isBlank()
                ? firstNonBlank(plan.getClarificationQuestion(), "我没拿到可用上下文，你可以选中几条消息、指定时间范围，或发我文档链接。")
                : "我没拿到可用上下文：" + readableFailure(result.getMessage()) + "。你可以选中几条消息、指定时间范围，或发我文档链接。";
        return new ContextCollectionOutcome(
                ContextSufficiencyResult.insufficient(List.of("source_context"), question, "context collection failed"),
                workspaceContext
        );
    }

    private String readableFailure(String message) {
        if (message == null || message.isBlank()) {
            return "没有读取到可用内容";
        }
        String lower = message.toLowerCase();
        if (message.contains("LARK_DOC") || lower.contains("document") || lower.contains("docx")) {
            return "文档读取失败，可能是链接无效、机器人没有权限，或文档还没有分享给当前应用";
        }
        if (message.contains("IM_HISTORY") || lower.contains("message") || lower.contains("chat")) {
            return "聊天记录读取失败，可能是机器人没有权限，或这个时间范围内没有可用消息";
        }
        return "没有读取到可用内容";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
