package com.lark.imcollab.harness.presentation.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class PresentationConcurrencyConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService presentationIoExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    public PresentationConcurrencySettings presentationConcurrencySettings(
            @Value("${presentation.concurrency.image-resolve:8}") int imageResolveConcurrency,
            @Value("${presentation.concurrency.image-upload:4}") int imageUploadConcurrency,
            @Value("${presentation.concurrency.slide-xml:6}") int slideXmlConcurrency,
            @Value("${presentation.concurrency.slide-write:3}") int slideWriteConcurrency) {
        return new PresentationConcurrencySettings(
                imageResolveConcurrency,
                imageUploadConcurrency,
                slideXmlConcurrency,
                slideWriteConcurrency);
    }

    @Bean
    public PresentationAssetSettings presentationAssetSettings(
            @Value("${presentation.assets.max-image-tasks-per-slide:1}") int maxImageTasksPerSlide,
            @Value("${presentation.assets.cover-max-image-tasks:2}") int coverMaxImageTasks,
            @Value("${presentation.assets.download-cache-ttl-days:7}") long downloadCacheTtlDays,
            @Value("${presentation.assets.download-cache-max-files:500}") int downloadCacheMaxFiles,
            @Value("${presentation.assets.download-timeout-seconds:10}") int downloadTimeoutSeconds) {
        return new PresentationAssetSettings(
                maxImageTasksPerSlide,
                coverMaxImageTasks,
                downloadCacheTtlDays,
                downloadCacheMaxFiles,
                downloadTimeoutSeconds);
    }
}
