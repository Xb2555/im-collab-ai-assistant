package com.lark.imcollab.harness.presentation.config;

public record PresentationConcurrencySettings(
        int imageResolveConcurrency,
        int imageUploadConcurrency,
        int slideXmlConcurrency,
        int slideWriteConcurrency
) {
}
