package com.lark.imcollab.common.model.entity;

import com.lark.imcollab.common.model.enums.BusinessCode;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "通用响应")
public class BaseResponse<T> implements Serializable {

    @Schema(description = "业务状态码（0成功，非0失败）")
    private int code;

    @Schema(description = "响应数据")
    private T data;

    @Schema(description = "响应消息")
    private String message;

    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    public BaseResponse(BusinessCode businessCode) {
        this(businessCode.getCode(), null, businessCode.getMessage());
    }
}
