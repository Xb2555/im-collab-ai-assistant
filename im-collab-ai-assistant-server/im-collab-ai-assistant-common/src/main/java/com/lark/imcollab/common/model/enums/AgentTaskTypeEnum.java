package com.lark.imcollab.common.model.enums;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Agent任务类型枚举")
public enum AgentTaskTypeEnum {
    INTENT_PARSING,
    FETCH_CONTEXT,
    SEARCH_WEB,
    WRITE_DOC,
    WRITE_SLIDES,
    GENERATE_SUMMARY,
    WRITE_FLYSHEET
}
