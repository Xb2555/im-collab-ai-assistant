package com.lark.imcollab.gateway.im.dto;

import java.util.List;

public record LarkInviteChatMembersRequest(
        String chatId,
        List<String> userOpenIds
) {
}
