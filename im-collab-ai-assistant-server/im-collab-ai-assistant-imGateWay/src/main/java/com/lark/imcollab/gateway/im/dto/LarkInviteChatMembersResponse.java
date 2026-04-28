package com.lark.imcollab.gateway.im.dto;

import java.util.List;

public record LarkInviteChatMembersResponse(
        List<String> invalidOpenIds,
        List<String> notExistedOpenIds,
        List<String> pendingApprovalOpenIds
) {
}
