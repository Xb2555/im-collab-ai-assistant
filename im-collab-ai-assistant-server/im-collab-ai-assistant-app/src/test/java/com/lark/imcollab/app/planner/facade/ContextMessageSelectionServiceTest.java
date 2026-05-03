package com.lark.imcollab.app.planner.facade;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextMessageSelectionServiceTest {

    @Test
    void selectionPromptKeepsRawInstructionWhenSubAgentProvidesNarrowerSelectionInstruction() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextMessageSelectionService service = new ContextMessageSelectionService(chatModel, new ObjectMapper());
        when(chatModel.call(anyString()))
                .thenReturn(
                        "{\"selectedMessageIds\":[\"om-ack\"],\"sufficient\":true,\"reason\":\"exact marker matched\"}",
                        "{\"selectedMessageIds\":[],\"sufficient\":true,\"reason\":\"no missing\"}",
                        "{\"selectedMessageIds\":[\"om-ack\"],\"sufficient\":true,\"reason\":\"valid\"}"
                );

        List<LarkMessageHistoryItem> selected = service.select(
                "整理刚才关于 ACKUX220308 采购评审的讨论，输出摘要",
                "采购评审相关讨论",
                List.of(
                        item("om-old", "IMFLOW212541 采购评审材料：供应商甲报价12万。"),
                        item("om-ack", "ACKUX220308 采购评审背景：候选供应商A报价较低。")
                )
        );

        assertThat(selected).extracting(LarkMessageHistoryItem::messageId).containsExactly("om-ack");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatModel, times(3)).call(promptCaptor.capture());
        assertThat(promptCaptor.getAllValues().get(0))
                .contains("用户原句：整理刚才关于 ACKUX220308 采购评审的讨论，输出摘要")
                .contains("上下文筛选要求：采购评审相关讨论")
                .contains("精确限定是强约束");
    }

    @Test
    void exactMarkerInRawInstructionPrunesSemanticallySimilarButWrongTopicSelections() {
        ChatModel chatModel = mock(ChatModel.class);
        ContextMessageSelectionService service = new ContextMessageSelectionService(chatModel, new ObjectMapper());
        when(chatModel.call(anyString()))
                .thenReturn(
                        "{\"selectedMessageIds\":[\"om-old\",\"om-ack\"],\"sufficient\":true,\"reason\":\"topic matched\"}",
                        "{\"selectedMessageIds\":[\"om-old\",\"om-ack\"],\"sufficient\":true,\"reason\":\"kept\"}"
                );

        List<LarkMessageHistoryItem> selected = service.select(
                "整理刚才关于 ACKUX220842 采购评审的讨论，输出摘要",
                "采购评审相关讨论",
                List.of(
                        item("om-old", "IMFLOW212541 采购评审材料：供应商甲报价12万。"),
                        item("om-ack", "ACKUX220842 采购评审背景：候选供应商A报价较低。")
                )
        );

        assertThat(selected).extracting(LarkMessageHistoryItem::messageId).containsExactly("om-ack");
    }

    private static LarkMessageHistoryItem item(String messageId, String content) {
        return new LarkMessageHistoryItem(
                messageId,
                null,
                null,
                null,
                "text",
                "2026-05-03T14:00:00Z",
                null,
                false,
                false,
                "chat-1",
                "ou-user",
                "open_id",
                "user",
                "tenant",
                content,
                List.of(),
                null,
                "测试用户",
                null
        );
    }
}
