package com.lark.imcollab.skills.lark.auth.dto;

public record AdminAuthorizationSession(
        String profileName,
        String deviceCode,
        String verificationUrl,
        int expiresIn,
        byte[] qrCodePng
) {

    public AdminAuthorizationSession(String deviceCode, String verificationUrl, int expiresIn, byte[] qrCodePng) {
        this(null, deviceCode, verificationUrl, expiresIn, qrCodePng);
    }
}
