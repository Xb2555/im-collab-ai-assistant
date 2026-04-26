package com.lark.imcollab.gateway.auth.service;

import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationInfoResponse;
import com.lark.imcollab.gateway.auth.dto.LarkAdminAuthorizationStartResponse;

public interface IMAuthService {

    LarkAdminAuthorizationStartResponse startLarkAdminAuthorization();

    LarkAdminAuthorizationInfoResponse waitForLarkAdminAuthorization(String deviceCode);
}
