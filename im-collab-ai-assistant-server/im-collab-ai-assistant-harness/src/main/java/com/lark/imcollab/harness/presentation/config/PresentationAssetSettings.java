package com.lark.imcollab.harness.presentation.config;

public record PresentationAssetSettings(
        int maxImageTasksPerSlide,
        int coverMaxImageTasks,
        long queryCacheTtlHours,
        long downloadCacheTtlDays,
        int downloadCacheMaxFiles,
        int downloadTimeoutSeconds
) {
}
