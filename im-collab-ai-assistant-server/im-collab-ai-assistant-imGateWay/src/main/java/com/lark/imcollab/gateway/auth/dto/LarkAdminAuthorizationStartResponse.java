package com.lark.imcollab.gateway.auth.dto;

public record LarkAdminAuthorizationStartResponse(
        String deviceCode,
        String verificationUrl,
        int expiresIn,
        String qrCodePngBase64
) {
}
