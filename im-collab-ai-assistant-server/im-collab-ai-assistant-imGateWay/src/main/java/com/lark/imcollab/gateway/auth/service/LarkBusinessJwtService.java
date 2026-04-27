package com.lark.imcollab.gateway.auth.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lark.imcollab.gateway.auth.config.LarkOAuthProperties;
import com.lark.imcollab.gateway.auth.dto.LarkBusinessJwtClaims;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthUserResponse;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class LarkBusinessJwtService {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final LarkOAuthProperties properties;
    private final ObjectMapper objectMapper;

    public LarkBusinessJwtService(LarkOAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String issueToken(String sessionId, LarkOAuthUserResponse user, Duration ttl) {
        validateRequired(properties.getJwtSecret(), "jwtSecret");
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", properties.getJwtIssuer());
        payload.put("sub", subject(user));
        payload.put("jti", sessionId);
        payload.put("iat", now.getEpochSecond());
        payload.put("exp", expiresAt.getEpochSecond());

        String unsignedToken = base64UrlJson(header) + "." + base64UrlJson(payload);
        return unsignedToken + "." + sign(unsignedToken);
    }

    public Optional<LarkBusinessJwtClaims> parseToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        String unsignedToken = parts[0] + "." + parts[1];
        if (!MessageDigest.isEqual(sign(unsignedToken).getBytes(StandardCharsets.UTF_8),
                parts[2].getBytes(StandardCharsets.UTF_8))) {
            return Optional.empty();
        }
        try {
            Map<?, ?> payload = objectMapper.readValue(base64UrlDecode(parts[1]), Map.class);
            if (!properties.getJwtIssuer().equals(payload.get("iss"))) {
                return Optional.empty();
            }
            String sessionId = stringValue(payload.get("jti"));
            String subject = stringValue(payload.get("sub"));
            long exp = longValue(payload.get("exp"));
            Instant expiresAt = Instant.ofEpochSecond(exp);
            if (sessionId == null || subject == null || !expiresAt.isAfter(Instant.now())) {
                return Optional.empty();
            }
            return Optional.of(new LarkBusinessJwtClaims(sessionId, subject, expiresAt));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    private String subject(LarkOAuthUserResponse user) {
        if (user.openId() != null && !user.openId().isBlank()) {
            return user.openId();
        }
        if (user.userId() != null && !user.userId().isBlank()) {
            return user.userId();
        }
        if (user.unionId() != null && !user.unionId().isBlank()) {
            return user.unionId();
        }
        return "unknown";
    }

    private String sign(String unsignedToken) {
        validateRequired(properties.getJwtSecret(), "jwtSecret");
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return base64Url(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to sign business jwt", exception);
        }
    }

    private String base64UrlJson(Map<String, Object> value) {
        try {
            return base64Url(objectMapper.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize jwt payload", exception);
        }
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }

    private String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private void validateRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
    }
}
