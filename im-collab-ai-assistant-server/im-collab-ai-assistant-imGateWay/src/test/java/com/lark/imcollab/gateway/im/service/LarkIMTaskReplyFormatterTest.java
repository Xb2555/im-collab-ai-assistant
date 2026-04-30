package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.StepStatusEnum;
import com.lark.imcollab.common.model.enums.TaskStatusEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMTaskReplyFormatterTest {

    private final LarkIMTaskReplyFormatter formatter = new LarkIMTaskReplyFormatter();

    @Test
    void planReadyUsesShortConversationalSummary() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planBlueprint(PlanBlueprint.builder()
                        .successCriteria(List.of("文档结构清晰", "PPT 可汇报"))
                        .risks(List.of("资料不足"))
                        .planCards(List.of(
                                card("生成技术方案文档", PlanCardTypeEnum.DOC),
                                card("生成汇报 PPT", PlanCardTypeEnum.PPT)
                        ))
                        .build())
                .build();

        String text = formatter.planReady(session);

        assertThat(text).contains("我准备这样推进", "开始执行");
        assertThat(text).contains("生成技术方案文档", "生成汇报 PPT");
        assertThat(text).doesNotContain("成功标准", "风险", "交付物");
    }

    @Test
    void clarificationShowsAtMostTwoQuestions() {
        PlanTaskSession session = PlanTaskSession.builder()
                .activePromptSlots(List.of(
                        slot("面向老板还是技术评审？"),
                        slot("需要覆盖哪个时间范围？"),
                        slot("是否需要 PPT？")
                ))
                .build();

        String text = formatter.clarification(session);

        assertThat(text).contains("我还需要确认一下");
        assertThat(text).contains("面向老板还是技术评审？", "需要覆盖哪个时间范围？");
        assertThat(text).doesNotContain("是否需要 PPT？");
    }

    @Test
    void statusSummarizesRuntimeSnapshot() {
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.EXECUTING).build())
                .steps(List.of(
                        TaskStepRecord.builder().name("生成技术方案文档").status(StepStatusEnum.RUNNING).build(),
                        TaskStepRecord.builder().name("生成 PPT").status(StepStatusEnum.READY).build()
                ))
                .artifacts(List.of())
                .build();

        String text = formatter.status(snapshot);

        assertThat(text).contains("任务状态：正在执行", "当前步骤：生成技术方案文档", "步骤进度：0/2");
    }

    @Test
    void statusIgnoresSupersededSteps() {
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.WAITING_APPROVAL).build())
                .steps(List.of(
                        TaskStepRecord.builder().name("旧群内摘要").status(StepStatusEnum.SUPERSEDED).build(),
                        TaskStepRecord.builder().name("生成技术方案文档").status(StepStatusEnum.READY).build(),
                        TaskStepRecord.builder().name("生成一句话总结").status(StepStatusEnum.READY).build()
                ))
                .artifacts(List.of())
                .build();

        String text = formatter.status(snapshot);

        assertThat(text).contains("步骤进度：0/2");
        assertThat(text).doesNotContain("0/3", "旧群内摘要");
    }

    @Test
    void waitingApprovalStatusDoesNotSayCurrentlyProcessing() {
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.WAITING_APPROVAL).build())
                .steps(List.of(
                        TaskStepRecord.builder().name("生成技术方案文档").status(StepStatusEnum.READY).build(),
                        TaskStepRecord.builder().name("生成 PPT").status(StepStatusEnum.READY).build()
                ))
                .artifacts(List.of())
                .build();

        String text = formatter.status(snapshot);

        assertThat(text).contains("任务状态：等待你确认", "等待你确认计划", "确认后下一步：生成技术方案文档");
        assertThat(text).doesNotContain("当前步骤：生成技术方案文档", "正在处理：生成技术方案文档");
    }

    private static UserPlanCard card(String title, PlanCardTypeEnum type) {
        return UserPlanCard.builder()
                .title(title)
                .type(type)
                .build();
    }

    private static PromptSlotState slot(String prompt) {
        return PromptSlotState.builder()
                .prompt(prompt)
                .answered(false)
                .build();
    }
}
