package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.MediaAssetType;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class DocumentMediaAnchor implements Serializable {
    private String blockId;
    private MediaAssetType mediaType;
    private String plainText;
    private String nextBlockId;
    private String prevBlockId;
}
