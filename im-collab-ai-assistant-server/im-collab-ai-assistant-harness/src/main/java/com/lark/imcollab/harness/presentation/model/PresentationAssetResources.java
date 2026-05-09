package com.lark.imcollab.harness.presentation.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresentationAssetResources implements Serializable {

    private List<SlideAssetResource> slides;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlideAssetResource implements Serializable {
        private String slideId;
        private List<AssetResource> images;
        private List<AssetResource> illustrations;
        private List<AssetResource> diagrams;
        private List<AssetResource> charts;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetResource implements Serializable {
        private String assetId;
        private String sourceRef;
        private List<String> candidateUrls;
        private String sourceUrl;
        private String sourceSite;
        private String assetType;
        private String localTempPath;
        private String downloadStatus;
        private String fileToken;
        private String purpose;
        private String mimeType;
        private String fallbackSource;
    }
}
