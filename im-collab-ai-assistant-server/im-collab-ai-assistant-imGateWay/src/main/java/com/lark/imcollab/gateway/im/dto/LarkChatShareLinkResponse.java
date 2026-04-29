package com.lark.imcollab.gateway.im.dto;

public record LarkChatShareLinkResponse(
        String shareLink,
        String expireTime,
        boolean isPermanent
) {
}
