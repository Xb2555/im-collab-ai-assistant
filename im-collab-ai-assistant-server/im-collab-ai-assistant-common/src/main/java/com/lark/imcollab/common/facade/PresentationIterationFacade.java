package com.lark.imcollab.common.facade;

import com.lark.imcollab.common.model.dto.PresentationIterationRequest;
import com.lark.imcollab.common.model.vo.PresentationIterationVO;

public interface PresentationIterationFacade {

    PresentationIterationVO edit(PresentationIterationRequest request);
}
