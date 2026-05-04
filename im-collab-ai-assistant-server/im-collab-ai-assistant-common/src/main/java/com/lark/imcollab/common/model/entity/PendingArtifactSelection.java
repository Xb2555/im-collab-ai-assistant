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
public class PendingArtifactSelection implements Serializable {

    private String conversationKey;
    private String taskId;
    private String originalInstruction;
    private List<PendingArtifactCandidate> candidates;
    private Instant expiresAt;
}
