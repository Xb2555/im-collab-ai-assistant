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
public class PresentationImagePlan implements Serializable {

    private List<PageImagePlan> pagePlans;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PageImagePlan implements Serializable {
        private String slideId;
        private List<PresentationAssetPlan.AssetTask> contentImageTasks;
        private List<PresentationAssetPlan.AssetTask> illustrationTasks;
        private List<PresentationAssetPlan.DiagramTask> diagramTasks;
    }
}
