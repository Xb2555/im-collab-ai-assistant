package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.dto.LarkAuthenticatedSession;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.gateway.im.dto.LarkCreateChatRequest;
import com.lark.imcollab.gateway.im.dto.LarkInviteChatMembersRequest;
import com.lark.imcollab.gateway.im.dto.LarkSendMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LarkIMChatServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldListCurrentUserJoinedChats() throws Exception {
        FakeOpenApiClient client = new FakeOpenApiClient(objectMapper.readTree("""
                {
                  "items": [
                    {
                      "chat_id": "oc_123",
                      "name": "项目群",
                      "avatar": "https://example.com/avatar.png",
                      "description": "项目讨论",
                      "owner_id": "ou_owner",
                      "chat_status": "normal",
                      "external": false,
                      "tenant_key": "tenant_1"
                    }
                  ],
                  "has_more": true,
                  "page_token": "next-token"
                }
                """));
        LarkIMChatService service = service(client);

        var response = service.listChats("Bearer business-token", 50, "page-1", "ByActiveTimeDesc");

        assertThat(client.lastMethod).isEqualTo("GET");
        assertThat(client.lastPath).isEqualTo("/open-apis/im/v1/chats");
        assertThat(client.lastAccessToken).isEqualTo("user-access-token");
        assertThat(client.lastQueryParams)
                .containsEntry("page_size", "50")
                .containsEntry("page_token", "page-1")
                .containsEntry("sort_type", "ByActiveTimeDesc")
                .containsEntry("user_id_type", "open_id");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).chatId()).isEqualTo("oc_123");
        assertThat(response.items().get(0).name()).isEqualTo("项目群");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.pageToken()).isEqualTo("next-token");
    }

    @Test
    void shouldFilterCurrentUserJoinedChatsByCurrentBotMembership() throws Exception {
        FakeOpenApiClient client = new FakeOpenApiClient(
                objectMapper.readTree("""
                        {
                          "items": [
                            {"chat_id": "oc_with_bot", "name": "有机器人群", "chat_status": "normal"},
                            {"chat_id": "oc_without_bot", "name": "无机器人群", "chat_status": "normal"}
                          ],
                          "has_more": false
                        }
                        """),
                objectMapper.readTree("""
                        {
                          "items": [
                            {"chat_id": "oc_with_bot", "name": "有机器人群", "chat_status": "normal"}
                          ],
                          "has_more": false
                        }
                        """)
        );
        LarkIMChatService service = service(client);

        var response = service.listChats("Bearer business-token", 20, null, null, true);

        assertThat(client.tenantGetCalls).isEqualTo(1);
        assertThat(client.userGetCalls).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).chatId()).isEqualTo("oc_with_bot");
        assertThat(response.items().get(0).name()).isEqualTo("有机器人群");
    }

    @Test
    void shouldSendTextMessageAsCurrentUser() throws Exception {
        FakeOpenApiClient client = new FakeOpenApiClient(objectMapper.readTree("""
                {"message_id":"om_123","chat_id":"oc_123","create_time":"1770000000000"}
                """));
        LarkIMChatService service = service(client);

        var response = service.sendMessage(
                "Bearer business-token",
                new LarkSendMessageRequest("oc_123", "hello", "idem-1")
        );

        assertThat(client.lastMethod).isEqualTo("POST_USER");
        assertThat(client.lastPath).isEqualTo("/open-apis/im/v1/messages");
        assertThat(client.lastQueryParams)
                .containsEntry("receive_id_type", "chat_id")
                .containsEntry("uuid", "idem-1");
        JsonNode body = objectMapper.valueToTree(client.lastBody);
        assertThat(body.path("receive_id").asText()).isEqualTo("oc_123");
        assertThat(body.path("msg_type").asText()).isEqualTo("text");
        assertThat(objectMapper.readTree(body.path("content").asText()).path("text").asText()).isEqualTo("hello");
        assertThat(response.messageId()).isEqualTo("om_123");
    }

    @Test
    void shouldCreateChatWithTenantTokenAndCurrentUserOwner() throws Exception {
        FakeOpenApiClient client = new FakeOpenApiClient(objectMapper.readTree("""
                {"chat_id":"oc_new","name":"新项目群","chat_type":"private","owner_id":"ou_current"}
                """));
        LarkIMChatService service = service(client);

        var response = service.createChat(
                "Bearer business-token",
                new LarkCreateChatRequest("新项目群", "讨论项目", "private", List.of("ou_a", "ou_current"), "uuid-1")
        );

        assertThat(client.lastMethod).isEqualTo("POST_TENANT");
        assertThat(client.lastPath).isEqualTo("/open-apis/im/v1/chats");
        assertThat(client.lastQueryParams)
                .containsEntry("user_id_type", "open_id")
                .containsEntry("set_bot_manager", "true")
                .containsEntry("uuid", "uuid-1");
        JsonNode body = objectMapper.valueToTree(client.lastBody);
        assertThat(body.path("name").asText()).isEqualTo("新项目群");
        assertThat(body.path("owner_id").asText()).isEqualTo("ou_current");
        assertThat(body.path("user_id_list")).extracting(JsonNode::asText)
                .containsExactly("ou_a", "ou_current");
        assertThat(response.chatId()).isEqualTo("oc_new");
    }

    @Test
    void shouldSearchUsersForFrontendSelector() throws Exception {
        FakeOpenApiClient client = new FakeOpenApiClient(objectMapper.readTree("""
                {
                  "users": [
                    {
                      "open_id": "ou_123",
                      "user_id": "user_123",
                      "union_id": "on_123",
                      "name": "张三",
                      "en_name": "Zhang San",
                      "email": "zhangsan@example.com",
                      "avatar_url": "https://example.com/u.png"
                    }
                  ],
                  "has_more": false
                }
                """));
        LarkIMChatService service = service(client);

        var response = service.searchUsers("Bearer business-token", "张三", 20, null);

        assertThat(client.lastMethod).isEqualTo("GET");
        assertThat(client.lastPath).isEqualTo("/open-apis/search/v1/user");
        assertThat(client.lastQueryParams)
                .containsEntry("query", "张三")
                .containsEntry("page_size", "20");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).openId()).isEqualTo("ou_123");
        assertThat(response.items().get(0).name()).isEqualTo("张三");
    }

    @Test
    void shouldInviteSelectedUsersIntoChatAsCurrentUser() throws Exception {
        FakeOpenApiClient client = new FakeOpenApiClient(objectMapper.readTree("""
                {
                  "invalid_id_list": ["ou_invalid"],
                  "not_existed_id_list": [],
                  "pending_approval_id_list": ["ou_pending"]
                }
                """));
        LarkIMChatService service = service(client);

        var response = service.inviteMembers(
                "Bearer business-token",
                new LarkInviteChatMembersRequest("oc_123", List.of("ou_a", "ou_b"))
        );

        assertThat(client.lastMethod).isEqualTo("POST_USER");
        assertThat(client.lastPath).isEqualTo("/open-apis/im/v1/chats/oc_123/members");
        assertThat(client.lastQueryParams)
                .containsEntry("member_id_type", "open_id")
                .containsEntry("succeed_type", "1");
        JsonNode body = objectMapper.valueToTree(client.lastBody);
        assertThat(body.path("id_list")).extracting(JsonNode::asText)
                .containsExactly("ou_a", "ou_b");
        assertThat(response.invalidOpenIds()).containsExactly("ou_invalid");
        assertThat(response.pendingApprovalOpenIds()).containsExactly("ou_pending");
    }

    private LarkIMChatService service(FakeOpenApiClient client) {
        return new LarkIMChatService(
                new FakeOAuthService(),
                client,
                objectMapper
        );
    }

    private static final class FakeOAuthService extends LarkOAuthService {

        private FakeOAuthService() {
            super(null, null, null, null, null, null);
        }

        @Override
        public Optional<LarkAuthenticatedSession> resolveAuthenticatedSessionByBusinessToken(String businessToken) {
            if (!"business-token".equals(businessToken)) {
                return Optional.empty();
            }
            return Optional.of(new LarkAuthenticatedSession(
                    "user-access-token",
                    new LarkOAuthUserResponse("ou_current", "on_current", "user_current", "tenant_1",
                            "Current User", null)
            ));
        }
    }

    private static final class FakeOpenApiClient implements LarkOpenApiClient {

        private final JsonNode response;
        private final JsonNode tenantResponse;
        private String lastMethod;
        private String lastPath;
        private Map<String, String> lastQueryParams;
        private Object lastBody;
        private String lastAccessToken;
        private int userGetCalls;
        private int tenantGetCalls;

        private FakeOpenApiClient(JsonNode response) {
            this(response, response);
        }

        private FakeOpenApiClient(JsonNode response, JsonNode tenantResponse) {
            this.response = response;
            this.tenantResponse = tenantResponse;
        }

        @Override
        public JsonNode get(String path, Map<String, String> queryParams, String accessToken) {
            this.lastMethod = "GET";
            this.lastPath = path;
            this.lastQueryParams = queryParams;
            this.lastAccessToken = accessToken;
            this.userGetCalls++;
            return response;
        }

        @Override
        public JsonNode getWithTenantToken(String path, Map<String, String> queryParams) {
            this.lastMethod = "GET_TENANT";
            this.lastPath = path;
            this.lastQueryParams = queryParams;
            this.tenantGetCalls++;
            return tenantResponse;
        }

        @Override
        public JsonNode post(String path, Map<String, String> queryParams, Object body, String accessToken) {
            this.lastMethod = "POST_USER";
            this.lastPath = path;
            this.lastQueryParams = queryParams;
            this.lastBody = body;
            this.lastAccessToken = accessToken;
            return response;
        }

        @Override
        public JsonNode postWithTenantToken(String path, Map<String, String> queryParams, Object body) {
            this.lastMethod = "POST_TENANT";
            this.lastPath = path;
            this.lastQueryParams = queryParams;
            this.lastBody = body;
            return response;
        }
    }
}
