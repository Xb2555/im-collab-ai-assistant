package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.skills.lark.im.LarkMessageHistoryResponse;
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
    private final LarkIMChatService service = new LarkIMChatService(oauthService, openApiClient, objectMapper);

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
                      "body": {
                        "content": "{\\"text\\":\\"history\\"}"
                      }
                    }
                  ]
                }
                """);
        when(openApiClient.get(eq("/open-apis/im/v1/messages"), org.mockito.ArgumentMatchers.anyMap(), eq("user-access-token")))
                .thenReturn(data);

        LarkMessageHistoryResponse response = service.fetchMessageHistory(
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
    }
}
