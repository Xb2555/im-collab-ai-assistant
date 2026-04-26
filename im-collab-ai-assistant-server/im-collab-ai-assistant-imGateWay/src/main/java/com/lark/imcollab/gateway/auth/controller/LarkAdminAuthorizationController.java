package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationCompleteRequest;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.gateway.auth.service.IMAuthService;
import com.lark.imcollab.gateway.im.service.LarkCliProfileResolver;
import com.lark.imcollab.gateway.im.service.LarkIMListenerService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class LarkAdminAuthorizationController {

    private final IMAuthService imAuthService;
    private final LarkIMListenerService larkIMListenerService;
    private final LarkCliProfileResolver profileResolver;

    public LarkAdminAuthorizationController(
            IMAuthService imAuthService,
            LarkIMListenerService larkIMListenerService,
            LarkCliProfileResolver profileResolver
    ) {
        this.imAuthService = imAuthService;
        this.larkIMListenerService = larkIMListenerService;
        this.profileResolver = profileResolver;
    }

    @PostMapping("/start")
    public LarkAdminAuthorizationStartResponse startAuthorization() {
        return imAuthService.startLarkAdminAuthorization();
    }

    @PostMapping("/complete")
    public LarkAdminAuthorizationInfoResponse completeAuthorization(
            @RequestBody LarkAdminAuthorizationCompleteRequest request
    ) {
        LarkAdminAuthorizationInfoResponse response = imAuthService.waitForLarkAdminAuthorization(request.deviceCode());
        larkIMListenerService.startDefault(profileResolver.resolveConfiguredAppProfileName());
        return response;
    }
}
