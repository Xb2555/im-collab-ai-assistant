package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.im.dto.LarkMessageHistoryViewResponse;
import com.lark.imcollab.gateway.im.dto.LarkRealtimeMessage;
import com.lark.imcollab.gateway.im.event.LarkMessageEvent;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryItem;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LarkIMMessageProjectionServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LarkUserProfileHydrationService userProfileHydrationService = mock(LarkUserProfileHydrationService.class);
    private final LarkIMMessageProjectionService service =
            new LarkIMMessageProjectionService(userProfileHydrationService, objectMapper);

    @Test
    void shouldHydrateRealtimeTextMessageSender() {
        when(userProfileHydrationService.resolveByTenantAccessToken("ou_sender"))
                .thenReturn(new LarkUserProfile("ou_sender", "张三", "https://avatar.example/zhang.png"));

        LarkRealtimeMessage message = service.projectRealtime(new LarkMessageEvent(
                "event-1",
                "om_1",
                "oc_1",
                null,
                "group",
                "text",
                "hello",
                "hello",
                "ou_sender",
                "1608594809",
                false
        ));

        assertThat(message.senderName()).isEqualTo("张三");
        assertThat(message.senderAvatar()).isEqualTo("https://avatar.example/zhang.png");
    }

    @Test
    void shouldRenderRealtimeSystemMessageContent() {
        when(userProfileHydrationService.resolveByTenantAccessToken("ou_zhang"))
                .thenReturn(new LarkUserProfile("ou_zhang", "张三", null));
        when(userProfileHydrationService.resolveByTenantAccessToken("ou_li"))
                .thenReturn(new LarkUserProfile("ou_li", "李四", null));

        LarkRealtimeMessage message = service.projectRealtime(new LarkMessageEvent(
                "event-1",
                "om_1",
                "oc_1",
                null,
                "group",
                "system",
                "{\"template\":\"{from_user} 创建了群聊，并邀请了 {members}\",\"from_user\":{\"id\":\"ou_zhang\"},\"members\":[{\"id\":\"ou_li\"}]}",
                "{\"template\":\"{from_user} 创建了群聊，并邀请了 {members}\",\"from_user\":{\"id\":\"ou_zhang\"},\"members\":[{\"id\":\"ou_li\"}]}",
                null,
                "1608594809",
                false
        ));

        assertThat(message.content()).isEqualTo("张三 创建了群聊，并邀请了 李四");
    }

    @Test
    void shouldNormalizeHistorySystemVariablesAndReturnUserMap() throws Exception {
        String content = "{\"template\":\"{from_user} 创建了群聊，并邀请了 {members}\","
                + "\"from_user\":{\"id\":\"ou_zhang\"},\"members\":[{\"id\":\"ou_li\"}]}";
        when(userProfileHydrationService.resolveByUserAccessToken("user-token", "ou_zhang"))
                .thenReturn(new LarkUserProfile("ou_zhang", "张三", "https://avatar.example/zhang.png"));
        when(userProfileHydrationService.resolveByUserAccessToken("user-token", "ou_li"))
                .thenReturn(new LarkUserProfile("ou_li", "李四", "https://avatar.example/li.png"));

        LarkMessageHistoryViewResponse response = service.projectHistory(new LarkMessageHistoryResponse(
                List.of(new LarkMessageHistoryItem(
                        "om_1",
                        null,
                        null,
                        null,
                        "system",
                        null,
                        null,
                        false,
                        false,
                        "oc_1",
                        null,
                        null,
                        null,
                        null,
                        content,
                        List.of(),
                        null,
                        null,
                        null
                )),
                false,
                null
        ), "user-token");

        assertThat(response.items()).hasSize(1);
        assertThat(objectMapper.readTree(response.items().get(0).content()))
                .isEqualTo(objectMapper.readTree(contentWithVariables()));
        assertThat(response.userMap()).containsOnlyKeys("ou_zhang", "ou_li");
        assertThat(response.userMap().get("ou_zhang").name()).isEqualTo("张三");
        assertThat(response.userMap().get("ou_zhang").avatar()).isEqualTo("https://avatar.example/zhang.png");
        assertThat(response.userMap().get("ou_li").name()).isEqualTo("李四");
    }

    private String contentWithVariables() {
        return "{\"template\":\"{from_user} 创建了群聊，并邀请了 {members}\","
                + "\"from_user\":{\"id\":\"ou_zhang\"},\"members\":[{\"id\":\"ou_li\"}],"
                + "\"variables\":{\"from_user\":[\"张三\"],\"members\":[\"李四\"]}}";
    }
}
