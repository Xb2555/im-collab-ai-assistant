package com.lark.imcollab.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebMvcAsyncConfigTest {

    @Test
    void configuresBoundedMvcAsyncExecutor() {
        WebMvcAsyncConfig config = new WebMvcAsyncConfig();

        AsyncTaskExecutor executor = config.mvcAsyncTaskExecutor();

        assertThat(executor).isInstanceOf(ThreadPoolTaskExecutor.class);
        ThreadPoolTaskExecutor threadPool = (ThreadPoolTaskExecutor) executor;
        assertThat(threadPool.getCorePoolSize()).isEqualTo(4);
        assertThat(threadPool.getMaxPoolSize()).isEqualTo(16);
        assertThat(threadPool.getThreadNamePrefix()).isEqualTo("mvc-async-");
        threadPool.shutdown();
    }

    @Test
    void registersExecutorForSpringMvcAsyncSupport() {
        WebMvcAsyncConfig config = new WebMvcAsyncConfig();
        AsyncSupportConfigurer asyncSupportConfigurer = mock(AsyncSupportConfigurer.class);

        config.configureAsyncSupport(asyncSupportConfigurer);

        verify(asyncSupportConfigurer).setTaskExecutor(org.mockito.ArgumentMatchers.any(AsyncTaskExecutor.class));
        verify(asyncSupportConfigurer).setDefaultTimeout(WebMvcAsyncConfig.ASYNC_REQUEST_TIMEOUT_MILLIS);
    }
}
