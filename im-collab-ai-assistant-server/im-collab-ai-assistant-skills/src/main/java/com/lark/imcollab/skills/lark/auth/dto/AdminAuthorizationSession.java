package com.lark.imcollab.skills.lark.auth.dto;

public record AdminAuthorizationSession(
        String deviceCode,
        String verificationUrl,
        int expiresIn,
        byte[] qrCodePng
) {
}
