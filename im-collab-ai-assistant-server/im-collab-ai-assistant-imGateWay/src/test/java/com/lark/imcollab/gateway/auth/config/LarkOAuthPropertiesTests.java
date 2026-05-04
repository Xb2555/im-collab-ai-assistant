package com.lark.imcollab.gateway.auth.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LarkOAuthPropertiesTests {

    @Test
    void defaultScopesShouldIncludeContactUserDetailPermissions() {
        LarkOAuthProperties properties = new LarkOAuthProperties();

        assertThat(properties.getScopes())
                .contains("contact:contact.base:readonly")
                .contains("contact:user.base:readonly")
                .contains("search:message");
    }
}
