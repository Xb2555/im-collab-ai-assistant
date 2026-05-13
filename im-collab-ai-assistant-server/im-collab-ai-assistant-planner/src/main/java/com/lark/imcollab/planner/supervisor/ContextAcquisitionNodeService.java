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
        if (plan == null) {
            return new ContextCollectionOutcome(
                    ContextSufficiencyResult.insufficient(
                            List.of("source_context"),
                            "我还需要你提供要整理的聊天内容或文档链接。",
                            "missing context acquisition plan"
                    ),
                    workspaceContext
            );
        }
        if (!plan.isNeedCollection()) {
            return new ContextCollectionOutcome(
                    ContextSufficiencyResult.insufficient(
                            List.of("source_context"),
                            firstNonBlank(plan.getClarificationQuestion(), "我还需要你提供要整理的聊天内容或文档链接。"),
                            firstNonBlank(plan.getReason(), "missing context acquisition plan")
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
        String question = firstNonBlank(
                result == null ? null : result.getClarificationQuestion(),
                plan.getClarificationQuestion(),
                "我按你给的范围查了一遍，但没有找到能用来总结的内容。你想扩大时间范围、换个筛选条件，还是直接贴几条要整理的消息给我？"
        );
        return new ContextCollectionOutcome(
                ContextSufficiencyResult.insufficient(List.of("source_context"), question, "context collection failed"),
                workspaceContext
        );
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
