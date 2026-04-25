package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.common.cli.auth.dto.AdminAuthorizationRequest;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationCompleteRequest;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.gateway.auth.service.IMAuthService;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class LarkAdminAuthorizationController {

    private final IMAuthService imAuthService;

    public LarkAdminAuthorizationController(IMAuthService imAuthService) {
        this.imAuthService = imAuthService;
    }

    @PostMapping
    public LarkAdminAuthorizationStartResponse startAuthorization(@RequestBody AdminAuthorizationRequest request) {
        return imAuthService.startLarkAdminAuthorization(request);
    }

    @PostMapping("/complete")
    public LarkAdminAuthorizationInfoResponse completeAuthorization(
            @RequestBody LarkAdminAuthorizationCompleteRequest request
    ) {
        return imAuthService.waitForLarkAdminAuthorization(request.deviceCode());
    }

    @GetMapping("/getStatus")
    public AdminAuthorizationStatus getAdminAuthorizationStatus(){
        return imAuthService.getAdminAuthorizationStatus();
    }
}
