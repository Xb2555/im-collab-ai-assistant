package com.lark.imcollab.planner.supervisor;

import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.planner.replan.CardPlanPatchMerger;
import com.lark.imcollab.planner.replan.PlanAdjustmentInterpreter;
import com.lark.imcollab.planner.replan.PlanPatchCardDraft;
import com.lark.imcollab.planner.replan.PlanPatchIntent;
import com.lark.imcollab.planner.replan.PlanPatchOperation;
import com.lark.imcollab.planner.service.PlanQualityService;
import com.lark.imcollab.planner.service.PlannerSessionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplanNodeServiceTest {

    private final PlannerSessionService sessionService = mock(PlannerSessionService.class);
    private final PlanAdjustmentInterpreter adjustmentInterpreter = mock(PlanAdjustmentInterpreter.class);
    private final PlannerPatchTool patchTool = new PlannerPatchTool(new CardPlanPatchMerger());
    private final PlannerQuestionTool questionTool = mock(PlannerQuestionTool.class);
    private final PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
    private final PlanQualityService qualityService = mock(PlanQualityService.class);
    private final ReplanNodeService service = new ReplanNodeService(
            sessionService,
            adjustmentInterpreter,
            patchTool,
            questionTool,
            planningNodeService,
            qualityService
    );

    @Test
    void addStepPatchPersistsMergedPlanCards() {
        PlanTaskSession session = session();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session, "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.ADD_STEP)
                        .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                                .title("生成群内项目进展摘要")
                                .description("生成一段可以直接发到群里的项目进展摘要")
                                .type(PlanCardTypeEnum.SUMMARY)
                                .build()))
                        .confidence(0.92d)
                        .reason("add group progress summary")
                        .build());
        doAnswer(invocation -> {
            PlanTaskSession target = invocation.getArgument(0);
            PlanBlueprint merged = invocation.getArgument(1);
            target.setPlanBlueprint(merged);
            target.setPlanCards(merged.getPlanCards());
            target.setPlanningPhase(PlanningPhaseEnum.PLAN_READY);
            return target;
        }).when(qualityService).applyMergedPlanAdjustment(any(), any(), anyString());

        PlanTaskSession result = service.replan("task-1", "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null);

        assertThat(result.getPlanCards())
                .extracting(UserPlanCard::getTitle)
                .containsExactly("生成技术方案文档（含Mermaid架构图）", "基于技术方案文档生成汇报PPT初稿", "生成群内项目进展摘要");
        verify(questionTool, never()).askUser(any(), any());
    }

    @Test
    void dependencyOnlyChangeDoesNotPretendPlanAdjusted() {
        PlanTaskSession session = session();
        when(sessionService.getOrCreate("task-1")).thenReturn(session);
        when(sessionService.get("task-1")).thenReturn(session);
        when(adjustmentInterpreter.interpret(session, "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null))
                .thenReturn(PlanPatchIntent.builder()
                        .operation(PlanPatchOperation.ADD_STEP)
                        .newCardDrafts(List.of(PlanPatchCardDraft.builder()
                                .title("生成群内项目进展摘要")
                                .type(PlanCardTypeEnum.SUMMARY)
                                .build()))
                        .confidence(0.9d)
                        .build());
        PlannerPatchTool dependencyOnlyPatchTool = mock(PlannerPatchTool.class);
        when(dependencyOnlyPatchTool.merge(any(), any(), anyString())).thenReturn(PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档（含Mermaid架构图）", PlanCardTypeEnum.DOC, List.of()),
                        card("card-002", "基于技术方案文档生成汇报PPT初稿", PlanCardTypeEnum.PPT, List.of("card-001"))
                ))
                .build());
        ReplanNodeService dependencyOnlyService = new ReplanNodeService(
                sessionService,
                adjustmentInterpreter,
                dependencyOnlyPatchTool,
                questionTool,
                planningNodeService,
                qualityService
        );

        dependencyOnlyService.replan("task-1", "再加一条：最后输出一段可以直接发到群里的项目进展摘要", null);

        verify(questionTool).askUser(any(), any());
        verify(qualityService, never()).applyMergedPlanAdjustment(any(), any(), anyString());
    }

    private static PlanTaskSession session() {
        PlanBlueprint blueprint = PlanBlueprint.builder()
                .planCards(List.of(
                        card("card-001", "生成技术方案文档（含Mermaid架构图）", PlanCardTypeEnum.DOC, List.of()),
                        card("card-002", "基于技术方案文档生成汇报PPT初稿", PlanCardTypeEnum.PPT, List.of())
                ))
                .build();
        return PlanTaskSession.builder()
                .taskId("task-1")
                .planningPhase(PlanningPhaseEnum.PLAN_READY)
                .planBlueprint(blueprint)
                .planCards(blueprint.getPlanCards())
                .build();
    }

    private static UserPlanCard card(String cardId, String title, PlanCardTypeEnum type, List<String> dependsOn) {
        return UserPlanCard.builder()
                .cardId(cardId)
                .title(title)
                .description(title)
                .type(type)
                .status("PENDING")
                .dependsOn(dependsOn)
                .build();
    }
}
