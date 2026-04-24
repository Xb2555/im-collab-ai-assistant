package com.lark.imcollab.planner.service;

import com.lark.imcollab.common.model.entity.RoutePacket;
import com.lark.imcollab.common.model.entity.SubtaskPlan;
import com.lark.imcollab.common.model.entity.TaskIntent;
import com.lark.imcollab.common.model.enums.InputSourceEnum;
import com.lark.imcollab.common.model.enums.OutputTargetEnum;
import com.lark.imcollab.common.model.enums.SubtaskStatusEnum;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SupervisorService extends AbstractSupervisorLoop {

    private static final Pattern PAGE_LIMIT_PATTERN = Pattern.compile("(\\d+)\\s*(页|p|P)");
    private static final Pattern REPORT_TO_PATTERN = Pattern.compile("(?:汇报给|给)([^，。,\\s]{2,20})(?:汇报|看|审阅)?");

    /**
     * 构建路由结果
     *
     * @param rawInstruction 原始用户指令
     * @param inputSource    输入来源
     * @return 路由包
     */
    public RoutePacket buildRoutePacket(String rawInstruction, InputSourceEnum inputSource) {
        return runSupervisorLoop(rawInstruction, inputSource);
    }

    /**
     * 解析用户意图，封装 TaskIntent
     *
     * @param rawInstruction 原始用户指令
     * @param inputSource    输入来源
     * @return 结构化意图
     */
    @Override
    public TaskIntent parseTaskIntent(String rawInstruction, InputSourceEnum inputSource) {
        String instruction = rawInstruction == null ? "" : rawInstruction.trim();
        TaskIntent taskIntent = new TaskIntent();
        taskIntent.setRawInstruction(instruction);
        taskIntent.setInputSource(inputSource);
        taskIntent.setOutputTarget(resolveQuickTarget(instruction));
        taskIntent.setPageLimit(extractPageLimit(instruction));
        taskIntent.setStyleRequirement(extractStyleRequirement(instruction));
        taskIntent.setReportTo(extractReportTo(instruction));
        taskIntent.setKeyConstraints(extractConstraints(instruction));
        return taskIntent;
    }

    /**
     * 路由分发（关键词快速路由 / LLM 意图分析）
     *
     * @param taskIntent 结构化意图
     * @return 路由包
     */
    @Override
    public RoutePacket route(TaskIntent taskIntent) {
        RoutePacket routePacket = new RoutePacket();
        routePacket.setTaskId(UUID.randomUUID().toString());
        routePacket.setIntent(taskIntent);

        OutputTargetEnum quickTarget = taskIntent.getOutputTarget();
        if (quickTarget != OutputTargetEnum.UNKNOWN) {
            routePacket.setTarget(quickTarget);
            routePacket.setAiResolved(false);
            routePacket.setTransitionReason("keyword_route");
        } else {
            OutputTargetEnum llmResolvedTarget = resolveByFallbackAnalysis(taskIntent.getRawInstruction());
            routePacket.setTarget(llmResolvedTarget);
            routePacket.setAiResolved(true);
            routePacket.setTransitionReason("llm_intent_route");
        }

        routePacket.setSubtasks(buildSubtasks(routePacket.getTarget()));
        return routePacket;
    }

    /**
     * 是否继续主循环
     *
     * @param routePacket 当前路由包
     * @return 是否继续
     */
    @Override
    protected boolean needsFollowUp(RoutePacket routePacket) {
        return false;
    }

    /**
     * 主循环跟进处理
     *
     * @param routePacket 当前路由包
     * @return 下一轮路由包
     */
    @Override
    protected RoutePacket onFollowUp(RoutePacket routePacket) {
        return routePacket;
    }

    private OutputTargetEnum resolveQuickTarget(String instruction) {
        boolean hasDoc = containsAny(instruction, "文档", "doc", "方案");
        boolean hasPpt = containsAny(instruction, "ppt", "幻灯片", "演示稿");
        boolean hasSummary = containsAny(instruction, "总结", "摘要", "纪要");
        if (hasDoc && hasPpt) {
            return OutputTargetEnum.BOTH;
        }
        if (hasDoc) {
            return OutputTargetEnum.DOC;
        }
        if (hasPpt) {
            return OutputTargetEnum.PPT;
        }
        if (hasSummary) {
            return OutputTargetEnum.SUMMARY;
        }
        return OutputTargetEnum.UNKNOWN;
    }

    private OutputTargetEnum resolveByFallbackAnalysis(String instruction) {
        if (containsAny(instruction, "汇报", "老板", "评审")) {
            return OutputTargetEnum.BOTH;
        }
        if (containsAny(instruction, "整理", "复盘")) {
            return OutputTargetEnum.SUMMARY;
        }
        return OutputTargetEnum.UNKNOWN;
    }

    private Integer extractPageLimit(String instruction) {
        Matcher matcher = PAGE_LIMIT_PATTERN.matcher(instruction);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return null;
    }

    private String extractStyleRequirement(String instruction) {
        if (containsAny(instruction, "简洁", "精简")) {
            return "简洁";
        }
        if (containsAny(instruction, "正式", "严谨")) {
            return "正式";
        }
        if (containsAny(instruction, "活泼", "创意")) {
            return "创意";
        }
        return null;
    }

    private String extractReportTo(String instruction) {
        Matcher matcher = REPORT_TO_PATTERN.matcher(instruction);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private List<String> extractConstraints(String instruction) {
        List<String> constraints = new ArrayList<>();
        if (containsAny(instruction, "今天", "本周", "昨天")) {
            constraints.add("time_range_required");
        }
        if (containsAny(instruction, "不要超过", "不超过", "控制在")) {
            constraints.add("length_constraint");
        }
        if (containsAny(instruction, "必须", "务必")) {
            constraints.add("hard_constraint");
        }
        return constraints;
    }

    private List<SubtaskPlan> buildSubtasks(OutputTargetEnum target) {
        List<SubtaskPlan> subtasks = new ArrayList<>();
        if (target == OutputTargetEnum.BOTH) {
            subtasks.add(newSubtask(OutputTargetEnum.DOC));
            subtasks.add(newSubtask(OutputTargetEnum.PPT));
            return subtasks;
        }
        if (target == OutputTargetEnum.DOC
                || target == OutputTargetEnum.PPT
                || target == OutputTargetEnum.SUMMARY) {
            subtasks.add(newSubtask(target));
        }
        return subtasks;
    }

    private SubtaskPlan newSubtask(OutputTargetEnum target) {
        SubtaskPlan subtaskPlan = new SubtaskPlan();
        subtaskPlan.setSubtaskId(UUID.randomUUID().toString());
        subtaskPlan.setType(target);
        subtaskPlan.setStatus(SubtaskStatusEnum.PENDING);
        return subtaskPlan;
    }

    private boolean containsAny(String source, String... candidates) {
        String lowerSource = source == null ? "" : source.toLowerCase();
        for (String candidate : candidates) {
            if (lowerSource.contains(candidate.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
