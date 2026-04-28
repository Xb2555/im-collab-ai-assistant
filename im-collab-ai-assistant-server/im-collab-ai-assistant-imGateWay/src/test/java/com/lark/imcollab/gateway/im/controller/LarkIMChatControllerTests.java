package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.gateway.im.client.LarkOpenApiException;
import com.lark.imcollab.gateway.im.dto.LarkChatListResponse;
import com.lark.imcollab.gateway.im.dto.LarkChatSummary;
import com.lark.imcollab.gateway.im.dto.LarkCreateChatRequest;
import com.lark.imcollab.gateway.im.dto.LarkCreateChatResponse;
import com.lark.imcollab.gateway.im.dto.LarkInviteChatMembersRequest;
import com.lark.imcollab.gateway.im.dto.LarkInviteChatMembersResponse;
import com.lark.imcollab.gateway.im.dto.LarkSendMessageRequest;
import com.lark.imcollab.gateway.im.dto.LarkSendMessageResponse;
import com.lark.imcollab.gateway.im.dto.LarkUserSearchResponse;
import com.lark.imcollab.gateway.im.dto.LarkUserSummary;
import com.lark.imcollab.gateway.im.service.LarkIMChatService;
import com.lark.imcollab.gateway.im.service.LarkIMUnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LarkIMChatControllerTests {

    @Test
    void shouldExposeChatAndUserSelectionEndpoints() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkIMChatController(new StubChatService()))
                .setControllerAdvice(new LarkIMChatExceptionHandler())
                .build();

        mockMvc.perform(get("/api/im/chats/joined")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].chatId").value("oc_123"));

        mockMvc.perform(post("/api/im/messages/send")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"oc_123","text":"hello","idempotencyKey":"idem-1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messageId").value("om_123"));

        mockMvc.perform(post("/api/im/chats/createChat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"新项目群","userOpenIds":["ou_1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.chatId").value("oc_new"));

        mockMvc.perform(get("/api/im/organization-users/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .param("query", "张三"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].openId").value("ou_123"));

        mockMvc.perform(post("/api/im/chats/invite")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chatId":"oc_123","userOpenIds":["ou_1","ou_2"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pendingApprovalOpenIds[0]").value("ou_pending"));
    }

    @Test
    void shouldMapUnauthorizedAndLarkErrors() throws Exception {
        MockMvc mockMvc = MockMvcBuilders
                .standaloneSetup(new LarkIMChatController(new ErrorChatService()))
                .setControllerAdvice(new LarkIMChatExceptionHandler())
                .build();

        mockMvc.perform(get("/api/im/chats/joined"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        mockMvc.perform(get("/api/im/organization-users/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token")
                        .param("query", "permission"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("permission denied"));
    }

    private static class StubChatService extends LarkIMChatService {

        StubChatService() {
            super(null, null, null);
        }

        @Override
        public LarkChatListResponse listChats(String authorization, Integer pageSize, String pageToken, String sortType) {
            return listChats(authorization, pageSize, pageToken, sortType, false);
        }

        @Override
        public LarkChatListResponse listChats(
                String authorization,
                Integer pageSize,
                String pageToken,
                String sortType,
                boolean containsCurrentBot
        ) {
            return new LarkChatListResponse(List.of(new LarkChatSummary(
                    "oc_123", "项目群"
            )), false, null);
        }

        @Override
        public LarkSendMessageResponse sendMessage(String authorization, LarkSendMessageRequest request) {
            return new LarkSendMessageResponse("om_123", request.chatId(), "1770000000000");
        }

        @Override
        public LarkCreateChatResponse createChat(String authorization, LarkCreateChatRequest request) {
            return new LarkCreateChatResponse("oc_new", request.name(), "private", "ou_current");
        }

        @Override
        public LarkUserSearchResponse searchUsers(String authorization, String query, Integer pageSize, String pageToken) {
            return new LarkUserSearchResponse(List.of(new LarkUserSummary(
                    "ou_123", "user_123", "on_123", "张三", null, null, null
            )), false, null);
        }

        @Override
        public LarkInviteChatMembersResponse inviteMembers(
                String authorization,
                LarkInviteChatMembersRequest request
        ) {
            return new LarkInviteChatMembersResponse(List.of(), List.of(), List.of("ou_pending"));
        }
    }

    private static final class ErrorChatService extends StubChatService {

        @Override
        public LarkChatListResponse listChats(String authorization, Integer pageSize, String pageToken, String sortType) {
            return listChats(authorization, pageSize, pageToken, sortType, false);
        }

        @Override
        public LarkChatListResponse listChats(
                String authorization,
                Integer pageSize,
                String pageToken,
                String sortType,
                boolean containsCurrentBot
        ) {
            throw new LarkIMUnauthorizedException("Unauthorized");
        }

        @Override
        public LarkUserSearchResponse searchUsers(String authorization, String query, Integer pageSize, String pageToken) {
            throw new LarkOpenApiException(99991672, "permission denied");
        }
    }
}
