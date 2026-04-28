package com.lark.imcollab.common.exception;

import com.lark.imcollab.common.model.enums.BusinessCode;

public class ThrowUtils {

    /**
     * 条件成立则抛出异常
     *
     * @param condition        要判断的条件
     * @param runtimeException 要抛出的异常
     */
    public static void throwIf(boolean condition, RuntimeException runtimeException) {
        if (condition) {
            throw runtimeException;
        }
    }

    /**
     * 条件成立则抛异常
     *
     * @param condition 条件
     * @param businessCode 错误码
     */
    public static void throwIf(boolean condition, BusinessCode businessCode) {
        throwIf(condition, new AiAssistantException(businessCode));
    }

    /**
     * 条件成立则抛异常
     *
     * @param condition 条件
     * @param businessCode 错误码
     * @param message   错误信息
     */
    public static void throwIf(boolean condition, BusinessCode businessCode, String message) {
        throwIf(condition, new AiAssistantException(businessCode, message));
    }
}
