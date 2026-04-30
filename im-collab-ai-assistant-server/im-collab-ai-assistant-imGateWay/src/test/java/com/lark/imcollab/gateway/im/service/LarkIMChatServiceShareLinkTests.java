package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.gateway.im.dto.LarkChatShareLinkRequest;
import com.lark.imcollab.gateway.im.dto.LarkChatShareLinkResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkIMChatServiceShareLinkTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LarkOAuthService oauthService = mock(LarkOAuthService.class);
    private final LarkOpenApiClient openApiClient = mock(LarkOpenApiClient.class);
    private final LarkIMMessageProjectionService messageProjectionService = mock(LarkIMMessageProjectionService.class);
    private final LarkIMChatService service =
            new LarkIMChatService(oauthService, openApiClient, objectMapper, messageProjectionService);

    @Test
    void shouldCreateShareLinkWithUserAccessToken() throws Exception {
        when(oauthService.resolveAuthenticatedSessionByBusinessToken("biz-token"))
                .thenReturn(Optional.of(new LarkAuthenticatedSession(
                        "user-access-token",
                        new LarkOAuthUserResponse("ou_1", null, null, null, "User", null)
                )));
        JsonNode data = objectMapper.readTree("""
                {
                  "share_link": "https://applink.feishu.cn/client/chat/chatter/add_by_link?link_token=token",
                  "expire_time": "1609296809",
                  "is_permanent": false
                }
                """);
        when(openApiClient.post(
                eq("/open-apis/im/v1/chats/oc_1/link"),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.anyMap(),
                eq("user-access-token")
        )).thenReturn(data);

        LarkChatShareLinkResponse response = service.createShareLink(
                "Bearer biz-token",
                "oc_1",
                new LarkChatShareLinkRequest("year")
        );

        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(openApiClient).post(
                eq("/open-apis/im/v1/chats/oc_1/link"),
                eq(Map.of()),
                bodyCaptor.capture(),
                eq("user-access-token")
        );
        assertThat(bodyCaptor.getValue()).containsEntry("validity_period", "year");
        assertThat(response.shareLink()).isEqualTo("https://applink.feishu.cn/client/chat/chatter/add_by_link?link_token=token");
        assertThat(response.expireTime()).isEqualTo("1609296809");
        assertThat(response.isPermanent()).isFalse();
    }

    @Test
    void shouldRejectInvalidValidityPeriod() {
        when(oauthService.resolveAuthenticatedSessionByBusinessToken("biz-token"))
                .thenReturn(Optional.of(new LarkAuthenticatedSession(
                        "user-access-token",
                        new LarkOAuthUserResponse("ou_1", null, null, null, "User", null)
                )));

        assertThatThrownBy(() -> service.createShareLink(
                "Bearer biz-token",
                "oc_1",
                new LarkChatShareLinkRequest("month")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("validityPeriod must be week, year, or permanently");
    }
}
