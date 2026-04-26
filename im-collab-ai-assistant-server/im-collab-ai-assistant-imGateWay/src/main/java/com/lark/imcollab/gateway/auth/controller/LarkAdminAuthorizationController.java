package com.lark.imcollab.gateway.auth.controller;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationCompleteRequest;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.gateway.auth.service.IMAuthService;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfileCreateRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStartRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/auth")
public class LarkAdminAuthorizationController {

    private final IMAuthService imAuthService;

    public LarkAdminAuthorizationController(IMAuthService imAuthService) {
        this.imAuthService = imAuthService;
    }

    @GetMapping("/profiles")
    public List<AdminAuthorizationProfile> listProfiles() {
        return imAuthService.listLarkAuthorizationProfiles();
    }

    @PostMapping("/profiles")
    public AdminAuthorizationProfile createProfile(
            @RequestBody AdminAuthorizationProfileCreateRequest request
    ) {
        return imAuthService.createLarkAuthorizationProfile(request);
    }

    @PostMapping("/start")
    public LarkAdminAuthorizationStartResponse startAuthorization(
            @RequestBody AdminAuthorizationStartRequest request
    ) {
        return imAuthService.startLarkAdminAuthorization(request);
    }

    @PostMapping("/complete")
    public LarkAdminAuthorizationInfoResponse completeAuthorization(
            @RequestBody LarkAdminAuthorizationCompleteRequest request
    ) {
        return imAuthService.waitForLarkAdminAuthorization(request.deviceCode(), request.profileName());
    }

    @GetMapping("/status")
    public AdminAuthorizationStatus getAdminAuthorizationStatus(
            @RequestParam String profileName
    ) {
        return imAuthService.getAdminAuthorizationStatus(profileName);
    }
}
