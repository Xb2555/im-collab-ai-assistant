package com.lark.imcollab.common.utils;


import com.lark.imcollab.common.model.enums.BusinessCode;
import com.lark.imcollab.common.model.entity.BaseResponse;

/**
 * 快速构造响应结果的工具类
 */
public class ResultUtils {

    /**
     * 成功
     *
     * @param data 数据
     * @param <T>  数据类型
     * @return 响应
     */
    public static <T> BaseResponse<T> success(T data) {
        return new BaseResponse<>(0, data, "ok");
    }

    /**
     * 失败
     *
     * @param businessCode 错误码
     * @return 响应
     */
    public static BaseResponse<?> error(BusinessCode businessCode) {
        return new BaseResponse<>(businessCode);
    }

    /**
     * 失败
     *
     * @param code    错误码
     * @param message 错误信息
     * @return 响应
     */
    public static BaseResponse<?> error(int code, String message) {
        return new BaseResponse<>(code, null, message);
    }

    /**
     * 失败
     *
     * @param businessCode 错误码
     * @return 响应
     */
    public static BaseResponse<?> error(BusinessCode businessCode, String message) {
        return new BaseResponse<>(businessCode.getCode(), null, message);
    }
}