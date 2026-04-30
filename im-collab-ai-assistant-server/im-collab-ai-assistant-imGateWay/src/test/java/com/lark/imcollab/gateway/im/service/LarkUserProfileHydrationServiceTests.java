package com.lark.imcollab.gateway.im.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.im.client.LarkOpenApiClient;
import com.lark.imcollab.store.redis.RedisJsonStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkUserProfileHydrationServiceTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LarkOpenApiClient openApiClient = mock(LarkOpenApiClient.class);
    private final RedisJsonStore redisJsonStore = mock(RedisJsonStore.class);
    private final LarkUserProfileHydrationService service =
            new LarkUserProfileHydrationService(openApiClient, redisJsonStore);

    @Test
    void shouldReadNameAndAvatarFromContactUserDetailResponse() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "user": {
                    "open_id": "ou_1",
                    "name": "张三",
                    "avatar": {
                      "avatar_240": "https://avatar.example/240.png"
                    }
                  }
                }
                """);
        when(openApiClient.get(
                eq("/open-apis/contact/v3/users/ou_1"),
                eq(java.util.Map.of("user_id_type", "open_id")),
                eq("user-access-token")
        )).thenReturn(data);

        LarkUserProfile profile = service.resolveByUserAccessToken("user-access-token", "ou_1");

        assertThat(profile.name()).isEqualTo("张三");
        assertThat(profile.avatarUrl()).isEqualTo("https://avatar.example/240.png");
        verify(redisJsonStore).set(eq("imcollab:lark:user-profile:ou_1"), eq(profile), any(Duration.class));
    }

    @Test
    void shouldNotCacheEmptyProfile() throws Exception {
        JsonNode data = objectMapper.readTree("""
                {
                  "user": {
                    "open_id": "ou_1"
                  }
                }
                """);
        when(openApiClient.get(
                eq("/open-apis/contact/v3/users/ou_1"),
                eq(java.util.Map.of("user_id_type", "open_id")),
                eq("user-access-token")
        )).thenReturn(data);

        LarkUserProfile profile = service.resolveByUserAccessToken("user-access-token", "ou_1");

        assertThat(profile.name()).isNull();
        assertThat(profile.avatarUrl()).isNull();
        verify(redisJsonStore, never()).set(any(), any(), any());
    }

    @Test
    void shouldIgnoreAndDeleteCachedEmptyProfile() {
        when(redisJsonStore.get("imcollab:lark:user-profile:ou_1", LarkUserProfile.class))
                .thenReturn(Optional.of(new LarkUserProfile("ou_1", null, null)));

        service.resolveByUserAccessToken("user-access-token", "ou_1");

        verify(redisJsonStore).delete("imcollab:lark:user-profile:ou_1");
        verify(openApiClient).get(
                eq("/open-apis/contact/v3/users/ou_1"),
                eq(java.util.Map.of("user_id_type", "open_id")),
                eq("user-access-token")
        );
    }

    @Test
    void shouldSkipInvalidOrBotOpenId() {
        assertThat(service.resolveByTenantAccessToken("bot")).isNull();
        assertThat(service.resolveByTenantAccessToken("user_1")).isNull();
        assertThat(service.resolveByTenantAccessToken("")).isNull();

        verify(openApiClient, never()).getWithTenantToken(any(), any());
        verify(redisJsonStore, never()).get(any(), any());
    }
}
