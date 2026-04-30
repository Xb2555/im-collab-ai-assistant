package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.common.model.entity.BaseResponse;
import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.utils.ResultUtils;
import com.lark.imcollab.gateway.auth.dto.LarkFrontendUserResponse;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthCallbackRequest;
import com.lark.imcollab.gateway.auth.dto.LarkOAuthLoginResult;
import com.lark.imcollab.gateway.auth.service.LarkOAuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/auth")
public class LarkOAuthController {

    private final LarkOAuthService oauthService;

    public LarkOAuthController(LarkOAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping("/lark/login")
    public ResponseEntity<Void> login() {
        LarkOAuthLoginResult result = oauthService.startLoginForWebRedirect();
        return redirect(result.authorizationUri()).build();
    }

    @GetMapping("/login-url")
    public ResponseEntity<BaseResponse<?>> loginUrl() {
        LarkOAuthLoginResult result = oauthService.startLoginForQrEmbed();
        return ResponseEntity.ok(ResultUtils.success(result));
    }

    @PostMapping("/callback")
    public ResponseEntity<BaseResponse<?>> callback(@RequestBody LarkOAuthCallbackRequest request) {
        try {
            return ResponseEntity.ok(ResultUtils.success(oauthService.completeLogin(request.code(), request.state())));
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(ResultUtils.error(BusinessCode.PARAMS_ERROR, exception.getMessage()));
        }
    }

    @GetMapping("/callback")
    public ResponseEntity<BaseResponse<?>> callback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error
    ) {
        if (error != null && !error.isBlank()) {
            return ResponseEntity.badRequest().body(ResultUtils.error(BusinessCode.PARAMS_ERROR, error));
        }
        try {
            return ResponseEntity.ok(ResultUtils.success(oauthService.completeLogin(code, state)));
        } catch (RuntimeException exception) {
            return ResponseEntity.badRequest().body(ResultUtils.error(BusinessCode.PARAMS_ERROR, exception.getMessage()));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<BaseResponse<?>> me(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        Optional<LarkFrontendUserResponse> user = oauthService.findCurrentUserByBusinessToken(extractBearerToken(authorization));
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ResultUtils.error(BusinessCode.NOT_LOGIN_ERROR));
        }
        return ResponseEntity.ok(ResultUtils.success(user.get()));
    }

    @PostMapping("/logout")
    public ResponseEntity<BaseResponse<?>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        oauthService.logoutByBusinessToken(extractBearerToken(authorization));
        return ResponseEntity.ok(ResultUtils.success(true));
    }

    private ResponseEntity.BodyBuilder redirect(java.net.URI uri) {
        return ResponseEntity.status(HttpStatus.FOUND).location(uri);
    }

    private String extractBearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            return null;
        }
        return authorization.substring(prefix.length()).trim();
    }
}
