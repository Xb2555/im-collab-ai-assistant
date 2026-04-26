package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationFailedResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationPendingResponse;
import com.lark.imcollab.skills.lark.auth.AuthorizationFailedException;
import com.lark.imcollab.skills.lark.auth.AuthorizationPendingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = LarkAdminAuthorizationController.class)
public class LarkAdminAuthorizationExceptionHandler {

    @ExceptionHandler(AuthorizationPendingException.class)
    public LarkAdminAuthorizationPendingResponse authorizationPending(AuthorizationPendingException exception) {
        return new LarkAdminAuthorizationPendingResponse("authorization_pending", exception.getMessage());
    }

    @ExceptionHandler(AuthorizationFailedException.class)
    public LarkAdminAuthorizationFailedResponse authorizationFailed(AuthorizationFailedException exception) {
        return failed("authorization_failed", exception);
    }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    public LarkAdminAuthorizationFailedResponse invalidRequest(IllegalArgumentException exception) {
        return failed("invalid_request", exception);
    }

    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ExceptionHandler(IllegalStateException.class)
    public LarkAdminAuthorizationFailedResponse larkCliError(IllegalStateException exception) {
        return failed("lark_cli_error", exception);
    }

    private LarkAdminAuthorizationFailedResponse failed(String errorType, RuntimeException exception) {
        return new LarkAdminAuthorizationFailedResponse(
                "authorization_failed",
                errorType,
                readableMessage(exception),
                false
        );
    }

    private String readableMessage(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message;
    }
}
