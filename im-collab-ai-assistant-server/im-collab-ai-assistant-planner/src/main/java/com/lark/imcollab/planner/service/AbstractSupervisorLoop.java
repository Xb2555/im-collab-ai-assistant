package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.LoopMessage;
import com.lark.imcollab.common.model.entity.SupervisorLoopState;
import com.lark.imcollab.common.model.entity.SupervisorResult;
import com.lark.imcollab.common.model.entity.TaskIntent;
import com.lark.imcollab.common.model.enums.InputSourceEnum;

import java.util.ArrayList;
import java.util.UUID;

public abstract class AbstractSupervisorLoop {

    /**
     * Supervisor 主循环入口（对应文档：A→B→C→D→E→F→(G→H→C | I→J)）。
     * 循环状态由 {@link SupervisorLoopState} 承载；终态结果由 {@link SupervisorResult} 收敛。
     *
     * @param rawInstruction 原始用户指令
     * @param inputSource    输入来源
     * @return Supervisor 终态结果
     */
    public final SupervisorResult runSupervisorLoop(String rawInstruction, InputSourceEnum inputSource) {
        // A：接收用户指令并解析意图（raw -> TaskIntent）
        TaskIntent taskIntent = parseTaskIntent(rawInstruction, inputSource);
        // B：初始化 SupervisorLoopState（首包/计数器/恢复相关字段等）
        SupervisorLoopState state = initSupervisorLoopState(taskIntent);
        while (true) {
            // I/J：用户中断，直接终止
            if (isAborted(state)) return terminate(state, "aborted");
            // I/J：达到最大轮次，直接终止
            if (state.getTurnCount() > state.getMaxTurns()) return terminate(state, "max_turns");
            // D：预处理（预算、压缩、上下文准备等）
            preprocess(state);
            // E：模型调用（生成任务分发决策与 tool_use / 更新 state）
            callModel(state);

            // F：判断是否继续（needsFollowUp）
            if (!needsFollowUp(state)) {
                // I：无 tool_use 时，优先尝试恢复链路（compact/output/fallback）
                String recoveryReason = tryRecover(state);
                if (recoveryReason != null) {
                    advanceTurn(state, recoveryReason);
                    continue;
                }
                // J：无法恢复则输出终态
                return terminate(state, resolveTerminalReason(state));
            }
            // G：执行工具（调度子 Agent，子 Agent 工作图入口）
            dispatchSubAgents(state);
            // H：收集 tool 结果并更新状态
            collectToolResultsAndUpdateState(state);
            // C：进入下一轮
            advanceTurn(state, "next_turn");
        }
    }

    /**
     * 解析用户意图，封装 {@link TaskIntent}。
     *
     * @param rawInstruction 原始用户指令
     * @param inputSource    输入来源
     * @return 结构化意图
     */
    protected abstract TaskIntent parseTaskIntent(String rawInstruction, InputSourceEnum inputSource);

    /**
     * 初始化循环状态（对齐 Agent Loop 的 State 初始化）。
     * 建议：在实现类中将首包（路由、子任务规划等）写入 state（如 routePacket / messageTrace）。
     *
     * @param taskIntent 已解析意图
     * @return 循环状态
     */
    protected abstract SupervisorLoopState initSupervisorLoopState(TaskIntent taskIntent);

    /**
     * 创建状态壳：生成 taskId、初始化计数器与恢复相关字段。
     *
     * @param taskIntent 已解析意图
     * @return 基础循环状态
     */
    protected final SupervisorLoopState createBaseLoopState(TaskIntent taskIntent) {
        SupervisorLoopState state = new SupervisorLoopState();
        state.setTaskId(UUID.randomUUID().toString());
        state.setTaskIntent(taskIntent);
        state.setTurnCount(1);
        state.setMaxTurns(32);
        state.setMaxOutputTokensRecoveryCount(0);
        state.setHasAttemptedReactiveCompact(false);
        state.setMessageTrace(new ArrayList<>());
        state.setRoutePacket(null);
        return state;
    }

    /**
     * 预处理阶段（预算检查、上下文压缩、提示词准备等）。
     *
     * @param state 当前循环状态
     */
    protected void preprocess(SupervisorLoopState state) {
    }

    /**
     * 模型调用阶段：生成决策与 tool_use（写入 state）。
     *
     * @param state 当前循环状态
     */
    protected abstract void callModel(SupervisorLoopState state);

    /**
     * 判断本轮是否需要继续（通常由 tool_use / in_progress 子任务等触发）。
     *
     * @param state 当前循环状态
     * @return 是否继续下一轮
     */
    protected abstract boolean needsFollowUp(SupervisorLoopState state);

    /**
     * 恢复优先级序列（!needsFollowUp 后按序尝试）：
     * 1. tryCompactRecover  — context_compact_retry
     * 2. tryOutputRecover   — output_recovery
     * 3. tryFallbackRecover — fallback_model_retry
     * 返回非 null reason 表示继续循环；返回 null 表示无法恢复，进入终止。
     */
    private String tryRecover(SupervisorLoopState state) {
        if (tryCompactRecover(state)) return "context_compact_retry";
        if (tryOutputRecover(state)) return "output_recovery";
        if (tryFallbackRecover(state)) return "fallback_model_retry";
        return null;
    }

    protected boolean tryCompactRecover(SupervisorLoopState state) {
        return false;
    }

    /**
     * 输出截断恢复：注入续写提示、或提升输出上限等。
     *
     * @param state 当前循环状态
     * @return 是否触发恢复并继续循环
     */
    protected boolean tryOutputRecover(SupervisorLoopState state) {
        return false;
    }

    /**
     * 模型 fallback 恢复：切换模型或降级策略后重试。
     *
     * @param state 当前循环状态
     * @return 是否触发恢复并继续循环
     */
    protected boolean tryFallbackRecover(SupervisorLoopState state) {
        return false;
    }

    /**
     * 根据 state 决定终止原因，子类可覆盖以扩展（如 budget_exhausted）。
     * 默认：有错误 → failed，否则 → completed。
     */
    protected String resolveTerminalReason(SupervisorLoopState state) {
        if (state.getErrorMsg() != null) return "failed";
        return "completed";
    }

    /**
     * 用户中断检测：由外部写入 state.aborted。
     *
     * @param state 当前循环状态
     * @return 是否已中断
     */
    protected boolean isAborted(SupervisorLoopState state) {
        return Boolean.TRUE.equals(state.isAborted());
    }

    /**
     * 执行工具：调度子 Agent（可并行/串行，写入 state）。
     *
     * @param state 当前循环状态
     */
    protected abstract void dispatchSubAgents(SupervisorLoopState state);

    /**
     * 收集 tool 结果并更新状态：合并子 Agent 返回、更新子任务状态、产物引用等。
     *
     * @param state 当前循环状态
     */
    protected abstract void collectToolResultsAndUpdateState(SupervisorLoopState state);

    /**
     * 单轮结束：写入 transitionReason 并递增 turnCount。
     *
     * @param state            当前循环状态
     * @param transitionReason 本轮 continue 的原因
     */
    protected void advanceTurn(SupervisorLoopState state, String transitionReason) {
        state.setTransitionReason(transitionReason);
        state.setTurnCount(state.getTurnCount() + 1);
    }

    /**
     * 终止并收敛结果：将 state 映射为 {@link SupervisorResult}。
     *
     * @param state          当前循环状态
     * @param terminalReason 终止原因
     * @return Supervisor 终态结果
     */
    protected SupervisorResult terminate(SupervisorLoopState state, String terminalReason) {
        state.setTerminalReason(terminalReason);
        SupervisorResult result = new SupervisorResult();
        result.setTaskId(state.getTaskId());
        result.setTerminalReason(terminalReason);
        result.setErrorMsg(state.getErrorMsg());
        if (state.getRoutePacket() != null) {
            result.setSubtasks(state.getRoutePacket().getSubtasks());
            result.setDocUrl(state.getRoutePacket().getDocUrl());
            result.setPptUrl(state.getRoutePacket().getPptUrl());
        }
        return result;
    }

    /**
     * 追加一条循环内消息轨迹，用于调试/可观测性/回放。
     *
     * @param state   当前循环状态
     * @param message 轨迹消息
     */
    protected void appendMessage(SupervisorLoopState state, LoopMessage message) {
        state.getMessageTrace().add(message);
    }
}
