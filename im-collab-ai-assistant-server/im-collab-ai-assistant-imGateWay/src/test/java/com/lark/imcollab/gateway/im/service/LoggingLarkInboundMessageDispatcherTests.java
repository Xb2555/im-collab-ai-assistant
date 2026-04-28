package com.lark.imcollab.gateway.im.service;

import com.lark.imcollab.common.facade.PlannerPlanFacade;
import com.lark.imcollab.common.model.entity.PlanBlueprint;
import com.lark.imcollab.common.model.entity.PlanTaskSession;
import com.lark.imcollab.common.model.entity.PromptSlotState;
import com.lark.imcollab.common.model.entity.UserPlanCard;
import com.lark.imcollab.common.model.entity.WorkspaceContext;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.PlanCardTypeEnum;
import com.lark.imcollab.common.model.enums.PlanningPhaseEnum;
import com.lark.imcollab.gateway.im.dto.LarkInboundMessage;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.im.LarkBotMessageResult;
import com.lark.imcollab.skills.lark.im.LarkMessageReplyTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class LoggingLarkInboundMessageDispatcherTests {

    @Test
    void shouldBridgeInboundMetadataIntoWorkspaceContext() {
        PlannerPlanFacade plannerPlanFacade = mock(PlannerPlanFacade.class);
        LoggingLarkInboundMessageDispatcher dispatcher = new LoggingLarkInboundMessageDispatcher(plannerPlanFacade);
        when(plannerPlanFacade.plan(any(), any(), eq(null), eq(null)))
                .thenReturn(PlanTaskSession.builder().taskId("task-1").planningPhase(PlanningPhaseEnum.INTAKE).build());

        dispatcher.dispatch(new LarkInboundMessage(
                "evt-1",
                "msg-1",
                "chat-1",
                "thread-1",
                "p2p",
                "text",
                "generate weekly report",
                "user-1",
                "1773491924409",
                InputSourceEnum.LARK_PRIVATE_CHAT
        ));

        ArgumentCaptor<WorkspaceContext> captor = ArgumentCaptor.forClass(WorkspaceContext.class);
        verify(plannerPlanFacade).plan(eq("generate weekly report"), captor.capture(), eq(null), eq(null));

        WorkspaceContext context = captor.getValue();
        assertThat(context.getChatId()).isEqualTo("chat-1");
        assertThat(context.getThreadId()).isEqualTo("thread-1");
        assertThat(context.getMessageId()).isEqualTo("msg-1");
        assertThat(context.getSenderOpenId()).isEqualTo("user-1");
        assertThat(context.getChatType()).isEqualTo("p2p");
        assertThat(context.getInputSource()).isEqualTo("LARK_PRIVATE_CHAT");
        assertThat(context.getSelectedMessages()).containsExactly("generate weekly report");
    }

    @Test
    void shouldSendClarificationBackToPrivateChatWhenPlannerNeedsMoreInput() {
        PlannerPlanFacade plannerPlanFacade = mock(PlannerPlanFacade.class);
        StubReplyTool replyTool = new StubReplyTool();
        RecordingStreamService streamService = new RecordingStreamService();
        LoggingLarkInboundMessageDispatcher dispatcher =
                new LoggingLarkInboundMessageDispatcher(plannerPlanFacade, replyTool, streamService, null);
        when(plannerPlanFacade.plan(any(), any(), eq(null), eq(null)))
                .thenReturn(PlanTaskSession.builder()
                        .taskId("task-ask")
                        .planningPhase(PlanningPhaseEnum.ASK_USER)
                        .clarificationQuestions(List.of("Who is the target audience?"))
                        .build());

        dispatcher.dispatch(new LarkInboundMessage(
                "evt-ask-1",
                "msg-ask-1",
                "chat-ask-1",
                "thread-ask-1",
                "p2p",
                "text",
                "prepare a project briefing",
                "user-ask-1",
                "1773491924410",
                InputSourceEnum.LARK_PRIVATE_CHAT
        ));

        assertThat(replyTool.openId).isEqualTo("user-ask-1");
        assertThat(replyTool.privateText).isEqualTo("Who is the target audience?");
        assertThat(replyTool.privateSendCount).isEqualTo(1);
        assertThat(replyTool.replyCount).isZero();
        assertThat(streamService.sourceEvent.threadId()).isEqualTo("thread-ask-1");
        assertThat(streamService.text).isEqualTo("Who is the target audience?");
    }

    @Test
    void shouldReplyClarificationToGroupThreadUsingPromptSlots() {
        PlannerPlanFacade plannerPlanFacade = mock(PlannerPlanFacade.class);
        StubReplyTool replyTool = new StubReplyTool();
        RecordingStreamService streamService = new RecordingStreamService();
        LoggingLarkInboundMessageDispatcher dispatcher =
                new LoggingLarkInboundMessageDispatcher(plannerPlanFacade, replyTool, streamService, null);
        when(plannerPlanFacade.plan(any(), any(), eq(null), eq(null)))
                .thenReturn(PlanTaskSession.builder()
                        .taskId("task-ask-group")
                        .planningPhase(PlanningPhaseEnum.ASK_USER)
                        .activePromptSlots(List.of(
                                PromptSlotState.builder().slotKey("s1").prompt("What is the core message?").answered(false).build(),
                                PromptSlotState.builder().slotKey("s2").prompt("Do you need both doc and PPT?").answered(false).build()
                        ))
                        .build());

        dispatcher.dispatch(new LarkInboundMessage(
                "evt-ask-2",
                "msg-ask-2",
                "chat-ask-2",
                "thread-ask-2",
                "group",
                "text",
                "draft a solution summary",
                "user-ask-2",
                "1773491924411",
                InputSourceEnum.LARK_GROUP
        ));

        assertThat(replyTool.messageId).isEqualTo("msg-ask-2");
        assertThat(replyTool.text).isEqualTo(
                "\u4e3a\u4e86\u7ee7\u7eed\u89c4\u5212\uff0c\u8bf7\u8865\u5145\u4ee5\u4e0b\u4fe1\u606f\uff1a\n"
                        + "1. What is the core message?\n"
                        + "2. Do you need both doc and PPT?");
        assertThat(replyTool.replyCount).isEqualTo(1);
        assertThat(replyTool.privateSendCount).isZero();
        assertThat(streamService.sourceEvent.messageId()).isEqualTo("msg-ask-2");
        assertThat(streamService.sourceEvent.threadId()).isEqualTo("thread-ask-2");
        assertThat(streamService.text).isEqualTo(replyTool.text);
    }

    @Test
    void shouldReplyPlanReadyBackToPrivateChat() {
        PlannerPlanFacade plannerPlanFacade = mock(PlannerPlanFacade.class);
        StubReplyTool replyTool = new StubReplyTool();
        RecordingStreamService streamService = new RecordingStreamService();
        LoggingLarkInboundMessageDispatcher dispatcher =
                new LoggingLarkInboundMessageDispatcher(plannerPlanFacade, replyTool, streamService, null);
        when(plannerPlanFacade.plan(any(), any(), eq(null), eq(null)))
                .thenReturn(PlanTaskSession.builder()
                        .taskId("task-plan")
                        .planningPhase(PlanningPhaseEnum.PLAN_READY)
                        .planBlueprint(PlanBlueprint.builder()
                                .taskBrief("Prepare the weekly boss update")
                                .deliverables(List.of("Weekly report", "PPT deck"))
                                .successCriteria(List.of("Clear milestones", "Visible risks"))
                                .risks(List.of("Pending data from engineering"))
                                .planCards(List.of(
                                        UserPlanCard.builder()
                                                .title("Draft weekly report")
                                                .description("Summarize milestones and blockers")
                                                .type(PlanCardTypeEnum.DOC)
                                                .build(),
                                        UserPlanCard.builder()
                                                .title("Outline boss deck")
                                                .description("Convert the report into slides")
                                                .type(PlanCardTypeEnum.PPT)
                                                .build()
                                ))
                                .build())
                        .build());

        dispatcher.dispatch(new LarkInboundMessage(
                "evt-plan-1",
                "msg-plan-1",
                "chat-plan-1",
                "thread-plan-1",
                "p2p",
                "text",
                "plan the boss update",
                "user-plan-1",
                "1773491924413",
                InputSourceEnum.LARK_PRIVATE_CHAT
        ));

        assertThat(replyTool.openId).isEqualTo("user-plan-1");
        assertThat(replyTool.privateText).contains("\u89c4\u5212\u5df2\u751f\u6210");
        assertThat(replyTool.privateText).contains("Weekly report");
        assertThat(replyTool.privateText).contains("[DOC] Draft weekly report");
        assertThat(replyTool.privateText).contains("[PPT] Outline boss deck");
        assertThat(streamService.text).isEqualTo(replyTool.privateText);
    }

    @Test
    void shouldKeepDispatchingWhenClarificationReplyFails() {
        PlannerPlanFacade plannerPlanFacade = mock(PlannerPlanFacade.class);
        ThrowingReplyTool replyTool = new ThrowingReplyTool();
        RecordingStreamService streamService = new RecordingStreamService();
        RecordingRetryService retryService = new RecordingRetryService();
        LoggingLarkInboundMessageDispatcher dispatcher =
                new LoggingLarkInboundMessageDispatcher(plannerPlanFacade, replyTool, streamService, retryService);
        when(plannerPlanFacade.plan(any(), any(), eq(null), eq(null)))
                .thenReturn(PlanTaskSession.builder()
                        .taskId("task-ask-fail")
                        .planningPhase(PlanningPhaseEnum.ASK_USER)
                        .clarificationQuestions(List.of("When is the deadline?"))
                        .build());

        dispatcher.dispatch(new LarkInboundMessage(
                "evt-ask-3",
                "msg-ask-3",
                "chat-ask-3",
                "thread-ask-3",
                "p2p",
                "text",
                "prepare a boss update",
                "user-ask-3",
                "1773491924412",
                InputSourceEnum.LARK_PRIVATE_CHAT
        ));

        verify(plannerPlanFacade).plan(eq("prepare a boss update"), any(), eq(null), eq(null));
        verifyNoMoreInteractions(plannerPlanFacade);
        assertThat(streamService.sourceEvent.messageId()).isEqualTo("msg-ask-3");
        assertThat(streamService.text).isEqualTo("When is the deadline?");
        assertThat(retryService.privateOpenId).isEqualTo("user-ask-3");
        assertThat(retryService.privateText).isEqualTo("When is the deadline?");
    }

    private static final class StubReplyTool extends LarkMessageReplyTool {

        private String messageId;
        private String text;
        private String openId;
        private String privateText;
        private int replyCount;
        private int privateSendCount;

        StubReplyTool() {
            super(null);
        }

        @Override
        public void replyText(String messageId, String text) {
            this.messageId = messageId;
            this.text = text;
            this.replyCount++;
        }

        @Override
        public void replyText(String messageId, String text, String idempotencyKey) {
            replyText(messageId, text);
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text) {
            this.openId = openId;
            this.privateText = text;
            this.privateSendCount++;
            return new LarkBotMessageResult("om_bot", "oc_p2p", "1773491924411");
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text, String idempotencyKey) {
            return sendPrivateText(openId, text);
        }
    }

    private static final class ThrowingReplyTool extends LarkMessageReplyTool {

        ThrowingReplyTool() {
            super(null);
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text) {
            throw new IllegalStateException("reply failed");
        }

        @Override
        public LarkBotMessageResult sendPrivateText(String openId, String text, String idempotencyKey) {
            return sendPrivateText(openId, text);
        }
    }

    private static final class RecordingStreamService extends LarkIMMessageStreamService {

        private LarkMessageEvent sourceEvent;
        private String text;

        RecordingStreamService() {
            super(null, null);
        }

        @Override
        void publishBotReply(LarkMessageEvent sourceEvent, String text) {
            this.sourceEvent = sourceEvent;
            this.text = text;
        }
    }

    private static final class RecordingRetryService extends LarkOutboundMessageRetryService {

        private String privateOpenId;
        private String privateText;

        RecordingRetryService() {
            super(new StubReplyTool(), 1L, 1L, 1);
        }

        @Override
        public void enqueuePrivateText(String openId, String text, String idempotencyKey) {
            this.privateOpenId = openId;
            this.privateText = text;
        }
    }
}
