package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.gateway.im.dto.LarkMessageHistoryViewResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkIMChatServiceMessageHistoryTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LarkOAuthService oauthService = mock(LarkOAuthService.class);
    private final LarkOpenApiClient openApiClient = mock(LarkOpenApiClient.class);
    private final LarkUserProfileHydrationService userProfileHydrationService = mock(LarkUserProfileHydrationService.class);
    private final LarkIMMessageProjectionService messageProjectionService =
            new LarkIMMessageProjectionService(userProfileHydrationService, objectMapper);
    private final LarkIMChatService service =
            new LarkIMChatService(oauthService, openApiClient, objectMapper, messageProjectionService);

    @Test
    void shouldFetchMessageHistoryWithUserAccessToken() throws Exception {
        when(oauthService.resolveAuthenticatedSessionByBusinessToken("biz-token"))
                .thenReturn(Optional.of(new LarkAuthenticatedSession(
                        "user-access-token",
                        new LarkOAuthUserResponse("ou_1", null, null, null, "User", null)
                )));
        JsonNode data = objectMapper.readTree("""
                {
                  "has_more": false,
                  "items": [
                    {
                      "message_id": "om_1",
                      "msg_type": "text",
                      "chat_id": "oc_1",
                      "sender": {
                        "id": "ou_sender",
                        "id_type": "open_id",
                        "sender_type": "user"
                      },
                      "body": {
                        "content": "{\\"text\\":\\"history\\"}"
                      }
                    }
                  ]
                }
                """);
        when(openApiClient.get(eq("/open-apis/im/v1/messages"), org.mockito.ArgumentMatchers.anyMap(), eq("user-access-token")))
                .thenReturn(data);
        when(userProfileHydrationService.resolveByUserAccessToken("user-access-token", "ou_sender"))
                .thenReturn(new LarkUserProfile("ou_sender", "张三", "https://avatar.example/zhang.png"));

        LarkMessageHistoryViewResponse response = service.fetchMessageHistory(
                "Bearer biz-token",
                "chat",
                "oc_1",
                "1608594809",
                "1609296809",
                "ByCreateTimeDesc",
                50,
                "page-1",
                "user_card_content"
        );

        ArgumentCaptor<Map<String, String>> queryCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openApiClient).get(eq("/open-apis/im/v1/messages"), queryCaptor.capture(), eq("user-access-token"));
        assertThat(queryCaptor.getValue()).containsEntry("container_id_type", "chat")
                .containsEntry("container_id", "oc_1")
                .containsEntry("start_time", "1608594809")
                .containsEntry("end_time", "1609296809")
                .containsEntry("sort_type", "ByCreateTimeDesc")
                .containsEntry("page_size", "50")
                .containsEntry("page_token", "page-1")
                .containsEntry("card_msg_content_type", "user_card_content");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).content()).isEqualTo("{\"text\":\"history\"}");
        assertThat(response.items().get(0).senderName()).isEqualTo("张三");
        assertThat(response.items().get(0).senderAvatar()).isEqualTo("https://avatar.example/zhang.png");
        assertThat(response.userMap()).containsOnlyKeys("ou_sender");
        assertThat(response.userMap().get("ou_sender").name()).isEqualTo("张三");
        assertThat(response.userMap().get("ou_sender").avatar()).isEqualTo("https://avatar.example/zhang.png");
    }

    @Test
    void shouldNormalizeSystemMessageVariablesAndReturnUserMap() throws Exception {
        when(oauthService.resolveAuthenticatedSessionByBusinessToken("biz-token"))
                .thenReturn(Optional.of(new LarkAuthenticatedSession(
                        "user-access-token",
                        new LarkOAuthUserResponse("ou_1", null, null, null, "User", null)
                )));
        JsonNode data = objectMapper.readTree("""
                {
                  "has_more": false,
                  "items": [
                    {
                      "message_id": "om_system",
                      "msg_type": "system",
                      "chat_id": "oc_1",
                      "body": {
                        "content": "{\\"template\\":\\"{from_user} 创建了群聊，并邀请了 {members}\\",\\"from_user\\":{\\"id\\":\\"ou_zhang\\"},\\"members\\":[{\\"id\\":\\"ou_li\\"}]}"
                      }
                    }
                  ]
                }
                """);
        when(openApiClient.get(eq("/open-apis/im/v1/messages"), org.mockito.ArgumentMatchers.anyMap(), eq("user-access-token")))
                .thenReturn(data);
        when(userProfileHydrationService.resolveByUserAccessToken("user-access-token", "ou_zhang"))
                .thenReturn(new LarkUserProfile("ou_zhang", "张三", "https://avatar.example/zhang.png"));
        when(userProfileHydrationService.resolveByUserAccessToken("user-access-token", "ou_li"))
                .thenReturn(new LarkUserProfile("ou_li", "李四", "https://avatar.example/li.png"));

        LarkMessageHistoryViewResponse response = service.fetchMessageHistory(
                "Bearer biz-token",
                "chat",
                "oc_1",
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThat(response.items()).hasSize(1);
        assertThat(objectMapper.readTree(response.items().get(0).content()))
                .isEqualTo(objectMapper.readTree("{\"template\":\"{from_user} 创建了群聊，并邀请了 {members}\","
                        + "\"from_user\":{\"id\":\"ou_zhang\"},\"members\":[{\"id\":\"ou_li\"}],"
                        + "\"variables\":{\"from_user\":[\"张三\"],\"members\":[\"李四\"]}}"));
        assertThat(response.userMap()).containsOnlyKeys("ou_zhang", "ou_li");
        assertThat(response.userMap().get("ou_zhang").name()).isEqualTo("张三");
        assertThat(response.userMap().get("ou_zhang").avatar()).isEqualTo("https://avatar.example/zhang.png");
        assertThat(response.userMap().get("ou_li").name()).isEqualTo("李四");
        assertThat(response.userMap().get("ou_li").avatar()).isEqualTo("https://avatar.example/li.png");
    }
}
