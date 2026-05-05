package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.ArtifactRecord;
import com.lark.imcollab.common.model.entity.TaskRecord;
import com.lark.imcollab.common.model.entity.TaskRuntimeSnapshot;
import com.lark.imcollab.common.model.entity.TaskStepRecord;
import com.lark.imcollab.common.model.enums.ArtifactTypeEnum;
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
        assertThat(text).contains("你还可以指定");
        assertThat(text).contains("文档目前支持基于已选消息", "PPT 目前支持创建新的飞书演示稿");
        assertThat(text).contains("基于文档摘要生成 PPT");
        assertThat(text).contains("文档里补一段风险清单");
        assertThat(text).doesNotContain("成功标准", "交付物");
    }

    @Test
    void planReadyTurnsNounTitlesIntoActionSteps() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(
                                card("技术方案文档（含 Mermaid 架构图）", PlanCardTypeEnum.DOC),
                                card("汇报 PPT 初稿", PlanCardTypeEnum.PPT)
                        ))
                        .build())
                .build();

        String text = formatter.planReady(session);

        assertThat(text).contains("1. 先生成技术方案文档（含 Mermaid 架构图）");
        assertThat(text).contains("2. 再生成汇报 PPT 初稿");
        assertThat(text).doesNotContain("先技术方案文档", "再汇报 PPT 初稿");
    }

    @Test
    void singlePptPlanDoesNotStartWithThen() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(card("老板汇报 PPT 初稿", PlanCardTypeEnum.PPT)))
                        .build())
                .build();

        String text = formatter.planReady(session);

        assertThat(text).contains("1. 先生成老板汇报 PPT 初稿");
        assertThat(text).doesNotContain("1. 再生成老板汇报 PPT 初稿");
    }

    @Test
    void multiStepPlanUsesLastOnlyForFinalStep() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(
                                card("技术方案文档", PlanCardTypeEnum.DOC),
                                card("可直接发群的进展摘要", PlanCardTypeEnum.SUMMARY),
                                card("5页以内中文汇报PPT", PlanCardTypeEnum.PPT)
                        ))
                        .build())
                .build();

        String text = formatter.planReady(session);

        assertThat(text).contains("1. 先生成技术方案文档");
        assertThat(text).contains("2. 再生成可直接发群的进展摘要");
        assertThat(text).contains("3. 最后生成5页以内中文汇报PPT");
        assertThat(text).doesNotContain("2. 最后生成可直接发群的进展摘要");
    }

    @Test
    void planReadySuggestsCurrentCapabilityScopedEdits() {
        PlanTaskSession session = PlanTaskSession.builder()
                .taskId("task-1")
                .planBlueprint(PlanBlueprint.builder()
                        .planCards(List.of(
                                card("生成技术方案文档（面向老板）", PlanCardTypeEnum.DOC)
                        ))
                        .build())
                .build();

        String text = formatter.planReady(session);

        assertThat(text).contains("比如");
        assertThat(text).contains("文档里补一段风险清单");
        assertThat(text).contains("文档里加一段行动项");
        assertThat(text).doesNotContain("加一段群内摘要");
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

        assertThat(text).contains("我先确认两点");
        assertThat(text).contains("面向老板还是技术评审？", "需要覆盖哪个时间范围？");
        assertThat(text).doesNotContain("是否需要 PPT？");
    }

    @Test
    void singleClarificationUsesQuestionDirectly() {
        PlanTaskSession session = PlanTaskSession.builder()
                .activePromptSlots(List.of(slot("我还需要确认一下：你希望基于哪些材料整理？")))
                .build();

        String text = formatter.clarification(session);

        assertThat(text).isEqualTo("❓ 你希望基于哪些材料整理？");
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

    @Test
    void completedStatusDoesNotShowReadyStepAsCurrentStep() {
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.COMPLETED).build())
                .steps(List.of(
                        TaskStepRecord.builder().name("生成技术方案文档").status(StepStatusEnum.COMPLETED).build(),
                        TaskStepRecord.builder().name("项目风险评估表").status(StepStatusEnum.READY).build()
                ))
                .artifacts(List.of())
                .build();

        String text = formatter.status(snapshot);

        assertThat(text).contains("任务状态：已完成", "主执行链路已完成", "计划项：2 个");
        assertThat(text).doesNotContain("当前步骤：项目风险评估表", "步骤进度：1/2");
    }

    @Test
    void completedStatusIncludesArtifactLinks() {
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .task(TaskRecord.builder().status(TaskStatusEnum.COMPLETED).build())
                .artifacts(List.of(ArtifactRecord.builder()
                        .type(ArtifactTypeEnum.PPT)
                        .title("采购评审分析 PPT")
                        .url("https://example.feishu.cn/slides/ppt-token")
                        .preview("已修改 PPT 第 2 页")
                        .build()))
                .build();

        String text = formatter.status(snapshot);

        assertThat(text)
                .contains("任务状态：已完成", "已有产物：1 个", "[PPT] 采购评审分析 PPT",
                        "https://example.feishu.cn/slides/ppt-token");
    }

    @Test
    void failureIncludesFailedStepDetails() {
        PlanTaskSession session = PlanTaskSession.builder()
                .transitionReason("执行链路失败")
                .build();
        TaskRuntimeSnapshot snapshot = TaskRuntimeSnapshot.builder()
                .steps(List.of(
                        TaskStepRecord.builder()
                                .name("生成项目汇报 PPT")
                                .status(StepStatusEnum.FAILED)
                                .outputSummary("飞书 Slides 创建失败：用户授权已过期")
                                .build()))
                .build();

        String text = formatter.failure(session, snapshot);

        assertThat(text)
                .contains("这次处理没有成功：执行链路失败", "具体原因", "生成项目汇报 PPT",
                        "飞书 Slides 创建失败：用户授权已过期");
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
