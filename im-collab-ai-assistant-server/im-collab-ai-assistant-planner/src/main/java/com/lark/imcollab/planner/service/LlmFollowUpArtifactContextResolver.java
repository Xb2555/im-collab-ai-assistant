package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
import com.lark.imcollab.planner.intent.LlmChoiceResolver;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class LlmFollowUpArtifactContextResolver implements FollowUpArtifactContextResolver {

    private static final List<String> ALLOWED_CHOICES = List.of("DOC", "PPT", "SUMMARY", "NONE");

    private final LlmChoiceResolver llmChoiceResolver;

    public LlmFollowUpArtifactContextResolver(LlmChoiceResolver llmChoiceResolver) {
        this.llmChoiceResolver = llmChoiceResolver;
    }

    @Override
    public Optional<ArtifactTypeEnum> resolvePreferredArtifactType(
            PlanTaskSession previousSession,
            String userInput,
            WorkspaceContext workspaceContext
    ) {
        if (!hasText(userInput) || previousSession == null) {
            return Optional.empty();
        }
        String choice = llmChoiceResolver.chooseOne(
                buildInstruction(previousSession, userInput, workspaceContext),
                ALLOWED_CHOICES,
                """
                你负责判断：用户这句新的任务请求，是否在显式引用“上一轮刚完成任务”的已有产物，作为这次新任务的默认输入材料。

                只允许返回一个可选值：DOC、PPT、SUMMARY、NONE。

                规则：
                1. 只有当用户明确在说“这个文档 / 这份文档 / 刚才那个文档 / 基于这个PPT / 基于上一份材料”这类承接上一产物的意思时，才选择 DOC 或 PPT。
                2. 如果用户已经自己提供了新的文档链接、文件、消息材料，或者 workspaceContext 里已有显式 docRefs / attachmentRefs / selectedMessages，就选 NONE。
                3. 如果用户明确说“忽略上一个任务 / 不基于刚才那个 / 重新开始 / 不要用之前的产物”，选 NONE。
                4. 不要因为句子里偶然出现“文档”“PPT”这类词就选择；必须是“把上一轮完成产物当输入”的语义。
                5. 如果只是修改已有文档/PPT，而不是发起新的后续任务，也选 NONE。
                6. 不确定就选 NONE。
                """
        );
        String normalized = choice == null ? "" : choice.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "DOC" -> Optional.of(ArtifactTypeEnum.DOC);
            case "PPT" -> Optional.of(ArtifactTypeEnum.PPT);
            case "SUMMARY" -> Optional.of(ArtifactTypeEnum.SUMMARY);
            default -> Optional.empty();
        };
    }

    private String buildInstruction(
            PlanTaskSession previousSession,
            String userInput,
            WorkspaceContext workspaceContext
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("上一任务状态：")
                .append(previousSession.getPlanningPhase() == null ? "" : previousSession.getPlanningPhase().name())
                .append("\n");
        builder.append("上一任务原始指令：").append(safe(previousSession.getRawInstruction())).append("\n");
        builder.append("上一任务澄清目标：").append(safe(previousSession.getClarifiedInstruction())).append("\n");
        builder.append("用户这次输入：").append(userInput.trim()).append("\n");
        builder.append("当前显式上下文：");
        if (workspaceContext == null) {
            builder.append("无");
        } else {
            builder.append("docRefs=").append(sizeOf(workspaceContext.getDocRefs()))
                    .append(", attachmentRefs=").append(sizeOf(workspaceContext.getAttachmentRefs()))
                    .append(", selectedMessages=").append(sizeOf(workspaceContext.getSelectedMessages()))
                    .append(", selectedMessageIds=").append(sizeOf(workspaceContext.getSelectedMessageIds()));
        }
        return builder.toString();
    }

    private int sizeOf(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
