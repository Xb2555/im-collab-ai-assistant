package com.lark.imcollab.gateway.auth.store;

import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginSession;

import java.time.Duration;
import java.util.Optional;

public interface LarkUserSessionStore {

    void saveState(String state, Duration ttl);

    boolean consumeState(String state);

    void saveSession(String sessionId, LarkOAuthLoginSession session, Duration ttl);

    Optional<LarkOAuthLoginSession> findSession(String sessionId);

    void deleteSession(String sessionId);
}
