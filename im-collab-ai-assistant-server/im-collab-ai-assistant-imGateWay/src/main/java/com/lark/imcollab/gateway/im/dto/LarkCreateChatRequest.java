package com.lark.imcollab.gateway.im.dto;

import java.util.List;

public record LarkCreateChatRequest(
        String name,
        String description,
        String chatType,
        List<String> userOpenIds,
        String uuid
) {
}
