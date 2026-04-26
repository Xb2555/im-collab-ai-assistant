package com.lark.imcollab.skills.lark.auth;

public class AuthorizationFailedException extends RuntimeException {

    public AuthorizationFailedException(String message) {
        super(message);
    }
}
