package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.MediaAssetType;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

@Data
@Builder
public class ResolvedAsset implements Serializable {
    private MediaAssetType assetType;
    private String assetRef;
    private String uploadedFileToken;
    private String whiteboardToken;
    private TableModel tableModel;
    private String mimeType;
    private String caption;
    private String altText;
    private boolean requiresUpload;
    private boolean requiresCreation;
}
