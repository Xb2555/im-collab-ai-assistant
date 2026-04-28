package com.lark.imcollab.gateway.im.controller;

import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.gateway.im.dto.LarkCreateChatRequest;
import com.lark.imcollab.gateway.im.dto.LarkInviteChatMembersRequest;
import com.lark.imcollab.gateway.im.dto.LarkSendMessageRequest;
import com.lark.imcollab.gateway.im.service.LarkIMChatService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/im")
public class LarkIMChatController {

    private final LarkIMChatService chatService;

    public LarkIMChatController(LarkIMChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/chats/joined")
    public BaseResponse<?> listChats(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false) String sortType,
            @RequestParam(required = false, defaultValue = "false") boolean containsCurrentBot
    ) {
        return ResultUtils.success(chatService.listChats(authorization, pageSize, pageToken, sortType, containsCurrentBot));
    }

    @PostMapping("/messages/send")
    public BaseResponse<?> sendMessage(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody LarkSendMessageRequest request
    ) {
        return ResultUtils.success(chatService.sendMessage(authorization, request));
    }

    @PostMapping("/chats/createChat")
    public BaseResponse<?> createChat(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody LarkCreateChatRequest request
    ) {
        return ResultUtils.success(chatService.createChat(authorization, request));
    }

    @GetMapping("/organization-users/search")
    public BaseResponse<?> searchUsers(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam String query,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(required = false) String pageToken
    ) {
        return ResultUtils.success(chatService.searchUsers(authorization, query, pageSize, pageToken));
    }

    @PostMapping("/chats/invite")
    public BaseResponse<?> inviteMembers(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody LarkInviteChatMembersRequest request
    ) {
        return ResultUtils.success(chatService.inviteMembers(authorization, request));
    }
}
