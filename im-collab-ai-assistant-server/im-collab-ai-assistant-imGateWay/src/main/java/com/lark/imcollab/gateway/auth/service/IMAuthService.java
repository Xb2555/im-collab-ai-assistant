package com.lark.imcollab.gateway.auth.service;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfile;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationProfileCreateRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStartRequest;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;

import java.util.List;

public interface IMAuthService {

    List<AdminAuthorizationProfile> listLarkAuthorizationProfiles();

    AdminAuthorizationProfile createLarkAuthorizationProfile(AdminAuthorizationProfileCreateRequest request);

    LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationStartRequest request);

    LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode, String profileName);

    AdminAuthorizationStatus getAdminAuthorizationStatus(String profileName);
}
