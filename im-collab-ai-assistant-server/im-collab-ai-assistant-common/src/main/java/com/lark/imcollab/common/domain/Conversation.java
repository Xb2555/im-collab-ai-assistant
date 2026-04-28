package com.lark.imcollab.common.domain;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
public class Conversation implements Serializable {
    private String conversationId;
    private String userId;
    private String chatId;
    private String rawMessage;
    private String messageId;
    private Instant receivedAt;
}
