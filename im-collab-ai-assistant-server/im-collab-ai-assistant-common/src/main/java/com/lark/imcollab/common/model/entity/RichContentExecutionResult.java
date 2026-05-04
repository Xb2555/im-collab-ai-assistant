package com.lark.imcollab.common.model.entity;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
public class RichContentExecutionResult implements Serializable {
    private List<String> createdBlockIds;
    private List<String> createdAssetRefs;
    private long beforeRevision;
    private long afterRevision;
}
