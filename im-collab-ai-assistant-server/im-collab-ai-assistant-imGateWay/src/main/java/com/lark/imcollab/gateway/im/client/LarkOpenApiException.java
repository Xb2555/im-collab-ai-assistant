package com.lark.imcollab.gateway.im.client;

public class LarkOpenApiException extends RuntimeException {

    private final int larkCode;

    public LarkOpenApiException(int larkCode, String message) {
        super(message);
        this.larkCode = larkCode;
    }

    public int getLarkCode() {
        return larkCode;
    }
}
