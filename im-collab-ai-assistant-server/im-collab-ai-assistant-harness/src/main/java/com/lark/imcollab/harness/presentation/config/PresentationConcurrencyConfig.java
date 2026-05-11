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
}
