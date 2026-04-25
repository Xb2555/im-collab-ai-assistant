package com.lark.imcollab.gateway.auth.service;

import com.lark.imcollab.common.cli.auth.dto.AdminAuthorizationRequest;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;
import com.lark.imcollab.skills.lark.auth.dto.AdminAuthorizationStatus;

public interface IMAuthService {

    LarkAdminAuthorizationStartResponse startLarkAdminAuthorization(AdminAuthorizationRequest request);

    LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode);

    AdminAuthorizationStatus getAdminAuthorizationStatus();
}
