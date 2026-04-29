package com.lark.imcollab.gateway.im.event;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class LarkSdkMessageEventProcessorTests {

    @Test
    void shouldReturnBeforeSlowConsumerCompletes() throws Exception {
        LarkSdkMessageEventProcessor processor = new LarkSdkMessageEventProcessor(
                Executors.newSingleThreadExecutor()
        );
        LarkMessageEvent mappedEvent = new LarkMessageEvent(
                "event-1",
                "message-1",
                "oc_1",
                null,
                "group",
                "text",
                "hello",
                "ou_1",
                "1777453955475",
                false
        );
        CountDownLatch consumerStarted = new CountDownLatch(1);
        CountDownLatch releaseConsumer = new CountDownLatch(1);

        long startNanos = System.nanoTime();
        processor.submit(mappedEvent, ignored -> {
            consumerStarted.countDown();
            try {
                releaseConsumer.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertThat(elapsedMillis).isLessThan(100);
        assertThat(consumerStarted.await(1, TimeUnit.SECONDS)).isTrue();

        releaseConsumer.countDown();
        processor.close();
    }
}
