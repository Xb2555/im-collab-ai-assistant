package com.lark.imcollab.common.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingTaskSelection implements Serializable {

    private String conversationKey;
    private String originalInstruction;
    private List<PendingTaskCandidate> candidates;
    private Instant expiresAt;
}
