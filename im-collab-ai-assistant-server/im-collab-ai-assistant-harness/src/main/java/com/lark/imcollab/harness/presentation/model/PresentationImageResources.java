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
public class PresentationImageResources implements Serializable {

    private List<PageImageResource> resources;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageImageResource implements Serializable {
        private String slideId;
        private List<ResourceItem> contentImages;
        private List<ResourceItem> illustrations;
        private List<ResourceItem> diagrams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceItem implements Serializable {
        private List<String> candidateUrls;
        private String selectedUrl;
        private String sourceUrl;
        private String sourceSite;
        private String assetType;
        private String purpose;
        private String whiteboardDsl;
        private String mimeType;
        private String fallbackSource;
    }
}
