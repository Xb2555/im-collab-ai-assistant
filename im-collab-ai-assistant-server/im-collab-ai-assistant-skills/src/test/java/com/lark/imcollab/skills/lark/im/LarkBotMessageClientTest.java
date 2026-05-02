package com.lark.imcollab.skills.lark.im;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkBotMessageClientTest {

    @Test
    void normalizeIdempotencyKeyKeepsValidShortKeys() {
        assertThat(LarkBotMessageClient.normalizeIdempotencyKey("planner-review"))
                .isEqualTo("planner-review");
    }

    @Test
    void normalizeIdempotencyKeyHashesLongKeysForLarkUuidLimit() {
        String longKey = "planner-review::d2f254d0-48b7-4520-a652-a454a291cbdb::reviewed";

        String normalized = LarkBotMessageClient.normalizeIdempotencyKey(longKey);

        assertThat(normalized)
                .startsWith("im-")
                .hasSizeLessThanOrEqualTo(LarkBotMessageClient.MAX_UUID_LENGTH);
        assertThat(LarkBotMessageClient.normalizeIdempotencyKey(longKey)).isEqualTo(normalized);
    }
}
