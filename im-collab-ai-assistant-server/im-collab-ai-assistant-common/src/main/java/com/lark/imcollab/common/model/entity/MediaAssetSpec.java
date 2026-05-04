package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.MediaAssetSourceType;
import com.lark.imcollab.common.model.enums.MediaAssetType;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class MediaAssetSpec implements Serializable {
    private MediaAssetType assetType;
    private MediaAssetSourceType sourceType;
    private String sourceRef;
    private String mimeType;
    private String title;
    private String altText;
    private String caption;
    private String dimensionsHint;
    private String renderIntent;
    private String generationPrompt;
    private TableModel tableSchema;
    private String whiteboardDsl;
    private Map<String, String> layoutHints;
}
