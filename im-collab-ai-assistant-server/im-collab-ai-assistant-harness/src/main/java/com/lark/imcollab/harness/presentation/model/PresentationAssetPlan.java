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
public class PresentationAssetPlan implements Serializable {

    private List<SlideAssetPlan> slides;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SlideAssetPlan implements Serializable {
        private String slideId;
        private List<AssetTask> contentImageTasks;
        private List<AssetTask> illustrationTasks;
        private List<DiagramTask> diagramTasks;
        private List<AssetTask> chartTasks;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetTask implements Serializable {
        private String query;
        private String purpose;
        private String preferredSourceType;
        private List<String> preferredDomains;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiagramTask implements Serializable {
        private String mermaidCode;
        private String purpose;
    }
}
