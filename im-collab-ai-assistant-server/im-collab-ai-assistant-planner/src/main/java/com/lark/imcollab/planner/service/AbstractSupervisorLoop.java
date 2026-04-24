package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.RoutePacket;
import com.lark.imcollab.common.model.entity.TaskIntent;
import com.lark.imcollab.common.model.enums.InputSourceEnum;

public abstract class AbstractSupervisorLoop {

    /**
     * Supervisor 主循环入口
     *
     * @param rawInstruction 原始用户指令
     * @param inputSource    输入来源
     * @return 路由结果
     */
    public final RoutePacket runSupervisorLoop(String rawInstruction, InputSourceEnum inputSource) {
        TaskIntent taskIntent = parseTaskIntent(rawInstruction, inputSource);
        RoutePacket routePacket = route(taskIntent);
        while (true) {
            RoutePacket preprocessedPacket = preprocess(routePacket);
            if (!needsFollowUp(preprocessedPacket)) {
                return terminate(preprocessedPacket);
            }
            routePacket = onFollowUp(preprocessedPacket);
        }
    }

    /**
     * 预处理阶段（预算、压缩等）
     *
     * @param routePacket 当前路由包
     * @return 预处理后的路由包
     */
    protected RoutePacket preprocess(RoutePacket routePacket) {
        return routePacket;
    }

    /**
     * 解析用户意图
     *
     * @param rawInstruction 原始用户指令
     * @param inputSource    输入来源
     * @return 结构化意图
     */
    protected abstract TaskIntent parseTaskIntent(String rawInstruction, InputSourceEnum inputSource);

    /**
     * 路由分发阶段
     *
     * @param taskIntent 结构化意图
     * @return 路由包
     */
    protected abstract RoutePacket route(TaskIntent taskIntent);

    /**
     * 判断是否继续下一轮
     *
     * @param routePacket 当前路由包
     * @return 是否继续
     */
    protected abstract boolean needsFollowUp(RoutePacket routePacket);

    /**
     * 跟进处理阶段
     *
     * @param routePacket 当前路由包
     * @return 下一轮路由包
     */
    protected abstract RoutePacket onFollowUp(RoutePacket routePacket);

    /**
     * 终止阶段
     *
     * @param routePacket 当前路由包
     * @return 最终路由包
     */
    protected RoutePacket terminate(RoutePacket routePacket) {
        return routePacket;
    }
}
